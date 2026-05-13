/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */
package io.element.android.libraries.imageeditor.native_.ui.components

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * In-place stack blur for [Bitmap]s. Ported from Mario Klingemann's original
 * `StackBlur` (CC-BY 3.0) — the same algorithm Telegram-Android calls from its native
 * `image.cpp:stackBlurBitmap`. Two-pass integer arithmetic, runs in microseconds on the
 * small 20-dp-wide spoiler backdrop bitmaps we feed it.
 *
 * Why we ship our own port instead of `Modifier.blur`: Compose's `Modifier.blur` is a
 * RenderNode shader applied at composition time — the kernel reads from the underlying
 * layer buffer, which means the parent's background colour bleeds INTO the photo at
 * partial-alpha edges. On a black-background photo the bleed shows up as grey/white,
 * exactly the symptom the user reported. Operating on the bitmap bytes before display
 * sidesteps that entirely: the dst bitmap is opaque end-to-end, and no compositing
 * surface is involved.
 *
 * Alpha is forced to 0xFF on every output pixel. The spoiler backdrop is never meant to
 * be transparent, and writing solid alpha here removes any chance of edge-bleed into the
 * surrounding background regardless of the source bitmap's alpha channel.
 */
internal object StackBlur {
    fun blur(bitmap: Bitmap, radius: Int) {
        if (radius < 1) return
        // Native path first — see cpp/spoiler/StackBlur.cpp. JNI dispatch + locked
        // pixel access skips the IntArray copy this Kotlin fallback has to do, so on
        // hardware where libphotoedit.so loaded the cost is ~10× lower.
        if (SpoilerJni.available) {
            SpoilerJni.stackBlur(bitmap, radius)
            return
        }
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return
        val pix = IntArray(w * h)
        bitmap.getPixels(pix, 0, w, 0, 0, w, h)

        val wm = w - 1
        val hm = h - 1
        val div = radius + radius + 1
        val r = IntArray(w * h)
        val g = IntArray(w * h)
        val b = IntArray(w * h)
        val vmin = IntArray(max(w, h))

        var divsum = (div + 1) shr 1
        divsum *= divsum
        val dv = IntArray(256 * divsum)
        for (i in dv.indices) dv[i] = i / divsum

        var yw = 0
        var yi = 0
        val stack = Array(div) { IntArray(3) }
        val r1 = radius + 1

        // ----- Horizontal pass -----
        for (y in 0 until h) {
            var rinsum = 0; var ginsum = 0; var binsum = 0
            var routsum = 0; var goutsum = 0; var boutsum = 0
            var rsum = 0; var gsum = 0; var bsum = 0
            for (i in -radius..radius) {
                val p = pix[yi + min(wm, max(i, 0))]
                val sir = stack[i + radius]
                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                val rbs = r1 - abs(i)
                rsum += sir[0] * rbs
                gsum += sir[1] * rbs
                bsum += sir[2] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
            }
            var stackpointer = radius

            for (x in 0 until w) {
                r[yi] = dv[rsum]
                g[yi] = dv[gsum]
                b[yi] = dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                val stackstart = stackpointer - radius + div
                var sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                if (y == 0) vmin[x] = min(x + radius + 1, wm)
                val p = pix[yw + vmin[x]]
                sir[0] = (p shr 16) and 0xff
                sir[1] = (p shr 8) and 0xff
                sir[2] = p and 0xff
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi++
            }
            yw += w
        }

        // ----- Vertical pass -----
        for (x in 0 until w) {
            var rinsum = 0; var ginsum = 0; var binsum = 0
            var routsum = 0; var goutsum = 0; var boutsum = 0
            var rsum = 0; var gsum = 0; var bsum = 0
            var yp = -radius * w
            for (i in -radius..radius) {
                yi = max(0, yp) + x
                val sir = stack[i + radius]
                sir[0] = r[yi]; sir[1] = g[yi]; sir[2] = b[yi]
                val rbs = r1 - abs(i)
                rsum += r[yi] * rbs
                gsum += g[yi] * rbs
                bsum += b[yi] * rbs
                if (i > 0) {
                    rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                } else {
                    routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                }
                if (i < hm) yp += w
            }
            yi = x
            var stackpointer = radius
            for (y in 0 until h) {
                pix[yi] = (0xff shl 24) or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
                rsum -= routsum; gsum -= goutsum; bsum -= boutsum
                val stackstart = stackpointer - radius + div
                var sir = stack[stackstart % div]
                routsum -= sir[0]; goutsum -= sir[1]; boutsum -= sir[2]

                if (x == 0) vmin[y] = min(y + r1, hm) * w
                val p = x + vmin[y]
                sir[0] = r[p]; sir[1] = g[p]; sir[2] = b[p]
                rinsum += sir[0]; ginsum += sir[1]; binsum += sir[2]
                rsum += rinsum; gsum += ginsum; bsum += binsum

                stackpointer = (stackpointer + 1) % div
                sir = stack[stackpointer]
                routsum += sir[0]; goutsum += sir[1]; boutsum += sir[2]
                rinsum -= sir[0]; ginsum -= sir[1]; binsum -= sir[2]
                yi += w
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    }
}
