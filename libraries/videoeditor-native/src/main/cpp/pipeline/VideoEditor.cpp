// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "VideoEditor.h"

#include "../core/Logger.h"

#include <algorithm>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <vector>

namespace videoedit {

namespace {

// Round a dimension down to the nearest even number; H.264 + 4:2:0 chroma
// subsampling requires both width and height to be even.
int even(int v) { return v - (v & 1); }

} // namespace

bool VideoEditor::process(const std::string& inputPath, const std::string& outputPath,
                          const EditParams& params,
                          void (*progressCb)(float, void*),
                          void* progressCtx) {
    VideoDecoder dec;
    if (!dec.open(inputPath)) {
        VE_LOGE("process: open input failed");
        return false;
    }
    const auto& md = dec.metadata();

    // Resolve output dimensions.
    int outW = params.outputWidth  > 0 ? params.outputWidth  : even(static_cast<int>(md.width  * params.cropW));
    int outH = params.outputHeight > 0 ? params.outputHeight : even(static_cast<int>(md.height * params.cropH));
    outW = std::max(2, even(outW));
    outH = std::max(2, even(outH));

    EncoderConfig enc;
    enc.width      = outW;
    enc.height     = outH;
    enc.bitrateBps = params.bitrateBps;
    enc.frameRate  = params.frameRate > 0 ? params.frameRate : static_cast<int>(md.fps);
    enc.iFrameIntervalSec = 1;
    enc.rotation   = md.rotation;

    VideoEncoder encoder;
    if (!encoder.open(outputPath, enc)) {
        VE_LOGE("process: encoder open failed");
        return false;
    }

    // Seek the decoder to trim start (best-effort; av_seek_frame goes to nearest
    // keyframe at-or-before, then we frame-skip until PTS >= trimStartUs).
    const int64_t startUs = std::max<int64_t>(0, params.trimStartUs);
    const int64_t endUs   = (params.trimEndUs > 0) ? params.trimEndUs : md.durationUs;
    if (startUs > 0) dec.seekTo(startUs);

    // Decode loop. We allocate a per-frame RGBA scratch + (when crop is active)
    // a cropped-output scratch; the encoder takes RGBA at output dimensions.
    const size_t srcStride = static_cast<size_t>(md.width) * 4;
    std::vector<uint8_t> srcBuf(srcStride * md.height);
    std::vector<uint8_t> outBuf(static_cast<size_t>(outW) * outH * 4);

    const int srcCropX = static_cast<int>(params.cropX * md.width);
    const int srcCropY = static_cast<int>(params.cropY * md.height);
    const int srcCropW = static_cast<int>(params.cropW * md.width);
    const int srcCropH = static_cast<int>(params.cropH * md.height);

    int64_t firstPtsUs = -1;
    int64_t lastReportedFraction = -1;
    while (true) {
        int64_t ptsUs = 0;
        int r = dec.decodeNextFrame(srcBuf.data(), &ptsUs);
        if (r == 0) break;     // EOF
        if (r < 0) { encoder.finish(); return false; }
        if (ptsUs < startUs) continue;
        if (ptsUs > endUs)   break;
        if (firstPtsUs < 0)  firstPtsUs = ptsUs;
        const int64_t outPtsUs = ptsUs - firstPtsUs;

        // CPU filter pass — runs against the source RGBA *before* crop, so the
        // filter behaviour is independent of the output dimensions.
        applySimpleFilters(srcBuf.data(), md.width, md.height, params.filters);

        // Crop + resize: nearest-neighbour for the crop window into outBuf at
        // output dimensions. This is rough — proper bilinear is left for a
        // follow-up; for trim-only / native-resolution exports this path isn't
        // exercised because crop is full-frame and out dims match the source.
        if (params.cropW < 1.0f || params.cropH < 1.0f ||
            outW != md.width || outH != md.height) {
            for (int y = 0; y < outH; ++y) {
                const int srcY = srcCropY + (y * srcCropH) / outH;
                const uint8_t* srcRow = srcBuf.data() + static_cast<size_t>(srcY) * srcStride;
                uint8_t* dstRow = outBuf.data() + static_cast<size_t>(y) * outW * 4;
                for (int x = 0; x < outW; ++x) {
                    const int srcX = srcCropX + (x * srcCropW) / outW;
                    std::memcpy(dstRow + x * 4, srcRow + srcX * 4, 4);
                }
            }
            if (!encoder.encodeFrame(outBuf.data(), outPtsUs)) {
                VE_LOGE("encodeFrame failed at %lld", (long long)outPtsUs);
                encoder.finish();
                return false;
            }
        } else {
            if (!encoder.encodeFrame(srcBuf.data(), outPtsUs)) {
                VE_LOGE("encodeFrame failed at %lld", (long long)outPtsUs);
                encoder.finish();
                return false;
            }
        }

        // Progress: derive from PTS into trimmed range.
        if (progressCb) {
            const int64_t spanUs = std::max<int64_t>(1, endUs - startUs);
            const int64_t frac10000 = std::min<int64_t>(10000, ((ptsUs - startUs) * 10000) / spanUs);
            if (frac10000 > lastReportedFraction) {
                lastReportedFraction = frac10000;
                progressCb(static_cast<float>(frac10000) / 10000.0f, progressCtx);
            }
        }
    }

    if (params.keepAudio && md.hasAudio) {
        muxAudio(inputPath, encoder, params);
    }

    bool ok = encoder.finish();
    if (progressCb) progressCb(1.0f, progressCtx);
    return ok;
}

bool VideoEditor::muxAudio(const std::string& inputPath, VideoEncoder& enc,
                           const EditParams& params) {
    // We use AMediaExtractor (NDK) for the audio mux because it gives us raw
    // encoded packets that can go straight into the muxer with no re-encode.
    // Alternative: FFmpeg av_read_frame on the audio stream — same shape, but
    // MediaExtractor's format object plugs directly into AMediaMuxer_addTrack
    // without a reformatting step.
    int fd = ::open(inputPath.c_str(), O_RDONLY);
    if (fd < 0) {
        VE_LOGE("muxAudio: open() failed");
        return false;
    }
    AMediaExtractor* ex = AMediaExtractor_new();
    if (AMediaExtractor_setDataSourceFd(ex, fd, 0, INT64_MAX) != AMEDIA_OK) {
        VE_LOGE("muxAudio: setDataSourceFd failed");
        AMediaExtractor_delete(ex);
        ::close(fd);
        return false;
    }

    int audioTrack = -1;
    const size_t numTracks = AMediaExtractor_getTrackCount(ex);
    for (size_t i = 0; i < numTracks; ++i) {
        AMediaFormat* fmt = AMediaExtractor_getTrackFormat(ex, i);
        const char* mime = nullptr;
        AMediaFormat_getString(fmt, AMEDIAFORMAT_KEY_MIME, &mime);
        if (mime && std::strncmp(mime, "audio/", 6) == 0) {
            audioTrack = static_cast<int>(i);
            enc.addAudioPassthroughTrack(fmt);
            AMediaFormat_delete(fmt);
            break;
        }
        AMediaFormat_delete(fmt);
    }
    if (audioTrack < 0 || enc.audioTrackIndex() < 0) {
        AMediaExtractor_delete(ex);
        ::close(fd);
        return false;
    }
    AMediaExtractor_selectTrack(ex, audioTrack);
    AMediaExtractor_seekTo(ex, params.trimStartUs,
                           AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);

    const int64_t endUs = params.trimEndUs > 0 ? params.trimEndUs : INT64_MAX;
    std::vector<uint8_t> sampleBuf(64 * 1024);
    int64_t firstAudioPts = -1;
    while (true) {
        int64_t ptsUs = AMediaExtractor_getSampleTime(ex);
        if (ptsUs < 0 || ptsUs > endUs) break;
        if (ptsUs < params.trimStartUs) {
            if (!AMediaExtractor_advance(ex)) break;
            continue;
        }
        ssize_t s = AMediaExtractor_readSampleData(ex, sampleBuf.data(), sampleBuf.size());
        if (s < 0) break;
        if (firstAudioPts < 0) firstAudioPts = ptsUs;
        uint32_t flags = AMediaExtractor_getSampleFlags(ex);
        enc.writeAudioSample(sampleBuf.data(), static_cast<size_t>(s),
                             ptsUs - firstAudioPts, flags);
        if (!AMediaExtractor_advance(ex)) break;
    }

    AMediaExtractor_delete(ex);
    ::close(fd);
    return true;
}

} // namespace videoedit
