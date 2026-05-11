// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// High-level video edit pipeline: open input → loop frames → optional crop +
// scale → optional photo-style filters (Phase 2 hook — re-uses imageeditor-
// native shaders later) → encode → mux. Currently CPU-only frame processing;
// hooking in the GL FilterChain is queued for a follow-up so the audio mux
// path is exercised first.
//
// Trim is implemented at the decoder level: we seek to `startUs` and stop
// emitting frames once the decoded PTS exceeds `endUs`. Audio passthrough
// uses an extractor seeked to the same range.
#pragma once

#include "../decoder/VideoDecoder.h"
#include "../encoder/VideoEncoder.h"
#include "SimpleFilters.h"

#include <media/NdkMediaExtractor.h>
#include <string>

namespace videoedit {

struct EditParams {
    int64_t trimStartUs = 0;
    int64_t trimEndUs   = -1;     // -1 = until EOF

    // Output crop rectangle in normalised input-space [0, 1]. (0,0,1,1) = no crop.
    float cropX = 0.f, cropY = 0.f, cropW = 1.f, cropH = 1.f;

    // Output dimensions. If 0/0, derived from the input × crop. Always even
    // (encoder requirement for YUV chroma subsampling).
    int outputWidth  = 0;
    int outputHeight = 0;

    // Encoder knobs.
    int     bitrateBps = 4'000'000;
    int     frameRate  = 30;
    bool    keepAudio  = true;

    // Per-frame CPU filter (applied after decode → RGBA, before crop/encode).
    SimpleFilterParams filters{};

    bool isWholeVideo(int64_t durationUs) const {
        return trimStartUs <= 0 &&
               (trimEndUs < 0 || trimEndUs >= durationUs) &&
               cropX == 0.f && cropY == 0.f && cropW == 1.f && cropH == 1.f;
    }
};

class VideoEditor {
public:
    VideoEditor() = default;
    ~VideoEditor() = default;

    /**
     * Run the pipeline synchronously. Pass `progressCb` to receive 0..1 ticks
     * (caller must be re-entrant safe — we call from the encode thread). Return
     * value: true on success.
     */
    bool process(const std::string& inputPath, const std::string& outputPath,
                 const EditParams& params,
                 void (*progressCb)(float, void*) = nullptr,
                 void* progressCtx = nullptr);

private:
    // Audio passthrough — re-mux the source's audio track without re-encoding.
    // Seeks to `params.trimStartUs` first, stops at `params.trimEndUs`.
    bool muxAudio(const std::string& inputPath, VideoEncoder& enc,
                  const EditParams& params);
};

} // namespace videoedit
