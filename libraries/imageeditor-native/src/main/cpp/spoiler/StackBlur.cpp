// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "StackBlur.h"

#include <algorithm>
#include <cstdlib>
#include <vector>

namespace photoedit::spoiler {

void stackBlur(uint32_t* pix, int w, int h, int radius) {
    if (radius < 1 || w <= 0 || h <= 0) return;

    const int wm = w - 1;
    const int hm = h - 1;
    const int div = radius + radius + 1;
    const int r1 = radius + 1;
    const int wh = w * h;

    // Per-pixel intermediate buffers — the horizontal pass writes to r/g/b, the
    // vertical pass reads from them. Allocated once per call; for the spoiler use
    // case the bitmap is 20 dp wide (~60 px on a 3x display) so wh is in the
    // hundreds, well below the cost of any heap noise.
    std::vector<int> r(wh);
    std::vector<int> g(wh);
    std::vector<int> b(wh);
    std::vector<int> vmin(std::max(w, h));

    int divsum = (div + 1) >> 1;
    divsum *= divsum;
    std::vector<int> dv(256 * divsum);
    for (size_t i = 0; i < dv.size(); ++i) {
        dv[i] = static_cast<int>(i) / divsum;
    }

    // Circular stack of (r, g, b) triples, holding the running window of pixels in
    // the current row/column. Length is `div = 2 * radius + 1`.
    std::vector<int> stackR(div), stackG(div), stackB(div);

    // ----- Horizontal pass -----
    int yw = 0;
    int yi = 0;
    for (int y = 0; y < h; ++y) {
        int rinsum = 0, ginsum = 0, binsum = 0;
        int routsum = 0, goutsum = 0, boutsum = 0;
        int rsum = 0, gsum = 0, bsum = 0;

        for (int i = -radius; i <= radius; ++i) {
            const uint32_t p = pix[yi + std::min(wm, std::max(i, 0))];
            const int si = i + radius;
            stackR[si] = static_cast<int>((p >> 16) & 0xff);
            stackG[si] = static_cast<int>((p >> 8) & 0xff);
            stackB[si] = static_cast<int>(p & 0xff);
            const int rbs = r1 - std::abs(i);
            rsum += stackR[si] * rbs;
            gsum += stackG[si] * rbs;
            bsum += stackB[si] * rbs;
            if (i > 0) {
                rinsum += stackR[si]; ginsum += stackG[si]; binsum += stackB[si];
            } else {
                routsum += stackR[si]; goutsum += stackG[si]; boutsum += stackB[si];
            }
        }
        int stackpointer = radius;

        for (int x = 0; x < w; ++x) {
            r[yi] = dv[rsum];
            g[yi] = dv[gsum];
            b[yi] = dv[bsum];
            rsum -= routsum; gsum -= goutsum; bsum -= boutsum;
            const int stackstart = (stackpointer - radius + div) % div;
            routsum -= stackR[stackstart]; goutsum -= stackG[stackstart]; boutsum -= stackB[stackstart];

            if (y == 0) vmin[x] = std::min(x + radius + 1, wm);
            const uint32_t p = pix[yw + vmin[x]];
            stackR[stackstart] = static_cast<int>((p >> 16) & 0xff);
            stackG[stackstart] = static_cast<int>((p >> 8) & 0xff);
            stackB[stackstart] = static_cast<int>(p & 0xff);
            rinsum += stackR[stackstart]; ginsum += stackG[stackstart]; binsum += stackB[stackstart];
            rsum += rinsum; gsum += ginsum; bsum += binsum;

            stackpointer = (stackpointer + 1) % div;
            routsum += stackR[stackpointer]; goutsum += stackG[stackpointer]; boutsum += stackB[stackpointer];
            rinsum -= stackR[stackpointer]; ginsum -= stackG[stackpointer]; binsum -= stackB[stackpointer];
            ++yi;
        }
        yw += w;
    }

    // ----- Vertical pass -----
    for (int x = 0; x < w; ++x) {
        int rinsum = 0, ginsum = 0, binsum = 0;
        int routsum = 0, goutsum = 0, boutsum = 0;
        int rsum = 0, gsum = 0, bsum = 0;
        int yp = -radius * w;

        for (int i = -radius; i <= radius; ++i) {
            yi = std::max(0, yp) + x;
            const int si = i + radius;
            stackR[si] = r[yi]; stackG[si] = g[yi]; stackB[si] = b[yi];
            const int rbs = r1 - std::abs(i);
            rsum += r[yi] * rbs;
            gsum += g[yi] * rbs;
            bsum += b[yi] * rbs;
            if (i > 0) {
                rinsum += stackR[si]; ginsum += stackG[si]; binsum += stackB[si];
            } else {
                routsum += stackR[si]; goutsum += stackG[si]; boutsum += stackB[si];
            }
            if (i < hm) yp += w;
        }

        yi = x;
        int stackpointer = radius;
        for (int y = 0; y < h; ++y) {
            pix[yi] = (0xffu << 24)
                | (static_cast<uint32_t>(dv[rsum]) << 16)
                | (static_cast<uint32_t>(dv[gsum]) << 8)
                | static_cast<uint32_t>(dv[bsum]);
            rsum -= routsum; gsum -= goutsum; bsum -= boutsum;
            const int stackstart = (stackpointer - radius + div) % div;
            routsum -= stackR[stackstart]; goutsum -= stackG[stackstart]; boutsum -= stackB[stackstart];

            if (x == 0) vmin[y] = std::min(y + r1, hm) * w;
            const int p = x + vmin[y];
            stackR[stackstart] = r[p]; stackG[stackstart] = g[p]; stackB[stackstart] = b[p];
            rinsum += stackR[stackstart]; ginsum += stackG[stackstart]; binsum += stackB[stackstart];
            rsum += rinsum; gsum += ginsum; bsum += binsum;

            stackpointer = (stackpointer + 1) % div;
            routsum += stackR[stackpointer]; goutsum += stackG[stackpointer]; boutsum += stackB[stackpointer];
            rinsum -= stackR[stackpointer]; ginsum -= stackG[stackpointer]; binsum -= stackB[stackpointer];
            yi += w;
        }
    }
}

} // namespace photoedit::spoiler
