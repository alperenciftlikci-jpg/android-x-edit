// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Mario Klingemann's stack blur (CC-BY 3.0), the same algorithm Telegram-Android
// calls from `image.cpp:stackBlurBitmap`. Two-pass integer arithmetic on ARGB_8888
// pixels; alpha forced to 0xFF in the output (the spoiler backdrop is opaque, and
// solid alpha removes any possibility of edge bleed when the compose layer composites
// the result over the bubble background).
#pragma once

#include <cstdint>

namespace photoedit::spoiler {

// In-place stack blur. `pixels` must point to `width * height` ARGB_8888 packed
// pixels (the format Android's Bitmap.Config.ARGB_8888 hands back via
// AndroidBitmap_lockPixels). No-op when radius < 1 or dimensions <= 0.
void stackBlur(uint32_t* pixels, int width, int height, int radius);

} // namespace photoedit::spoiler
