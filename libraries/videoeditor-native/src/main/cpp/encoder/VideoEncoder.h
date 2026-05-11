// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// Hardware video encoder backed by Android's NDK MediaCodec C API. The encoder
// produces H.264 (video/avc) into an MP4 container via AMediaMuxer. Buffer-mode
// input — the caller hands us YUV420 frames; we don't currently use a Surface
// input path because:
//   1. The frame data we have post-filter is in CPU RGBA (filter chain reads
//      back from a GL FBO). Going RGBA → GL surface → encoder GL surface adds
//      a hop with no win over RGBA → YUV CPU conversion.
//   2. AMediaCodec's GL Surface API is fiddly across vendors; the buffer path
//      is the canonical "works everywhere" choice.
//
// Audio: Phase 3 doesn't transcode audio; the editor facade (Phase 4) reads
// the audio packets from the source via AMediaExtractor and feeds them into
// the same AMediaMuxer, so output keeps original audio at zero quality cost.
#pragma once

#include <cstdint>
#include <string>

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <media/NdkMediaMuxer.h>

extern "C" {
#include <libswscale/swscale.h>
}

namespace videoedit {

struct EncoderConfig {
    int      width        = 1280;
    int      height       = 720;
    int      bitrateBps   = 4'000'000;
    int      frameRate    = 30;
    int      iFrameIntervalSec = 1;
    int      rotation     = 0;            // muxer-level orientation hint
};

class VideoEncoder {
public:
    VideoEncoder() = default;
    ~VideoEncoder();
    VideoEncoder(const VideoEncoder&) = delete;
    VideoEncoder& operator=(const VideoEncoder&) = delete;

    /** Open `outputPath` (file system path) for writing as MP4. */
    bool open(const std::string& outputPath, const EncoderConfig& config);

    /** Open with an output file descriptor (for content:// destinations). The fd
     *  must be opened with read+write — the muxer seeks for the moov atom. */
    bool openFd(int fd, const EncoderConfig& config);

    /**
     * Add an audio track. `format` is an AMediaFormat describing the audio —
     * typically passed-through from the source (see AMediaExtractor track
     * format). Returns the muxer track index, or -1 on failure. Must be called
     * *before* [startMuxer], which finalises track configuration.
     *
     * Phase 4's editor wires this from the source extractor so audio is muxed
     * unchanged into the output.
     */
    int addAudioPassthroughTrack(AMediaFormat* format);

    /** Start the muxer. Call once, after all tracks are added. */
    bool startMuxer();

    /**
     * Encode a single RGBA frame at the given PTS (microseconds). RGBA buffer
     * must be `config.width * config.height * 4` bytes. Internally converts to
     * YUV420 (planar) before queueing into MediaCodec.
     */
    bool encodeFrame(const uint8_t* rgba, int64_t ptsUs);

    /** Mux a passthrough audio sample. `data` is encoded audio bytes
     *  (AAC/opus/etc.) as read from AMediaExtractor. */
    bool writeAudioSample(const uint8_t* data, size_t size, int64_t ptsUs, uint32_t flags);

    /** Flush remaining encoder output, stop encoder + muxer, close file. */
    bool finish();

    int audioTrackIndex() const { return audioTrackIdx_; }
    int videoTrackIndex() const { return videoTrackIdx_; }

private:
    bool createEncoder(const EncoderConfig& config);
    bool drainEncoder(bool endOfStream);

    AMediaCodec*  codec_       = nullptr;
    AMediaMuxer*  muxer_       = nullptr;
    int           muxerFd_     = -1;          // we own this fd if openFd was used
    int           videoTrackIdx_ = -1;
    int           audioTrackIdx_ = -1;
    bool          muxerStarted_ = false;
    EncoderConfig config_{};

    // RGBA → YUV420P conversion. We use FFmpeg's sws because it's already
    // linked for the decoder; rolling our own would duplicate effort.
    SwsContext* swsCtx_ = nullptr;
    uint8_t*    yuvBuffer_ = nullptr;
    int         yuvBufferSize_ = 0;

    int64_t lastVideoPtsUs_ = 0;
};

} // namespace videoedit
