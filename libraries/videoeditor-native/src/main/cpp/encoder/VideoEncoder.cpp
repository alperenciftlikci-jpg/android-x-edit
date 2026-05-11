// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "VideoEncoder.h"

#include "../core/Logger.h"

extern "C" {
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
}

#include <cstring>
#include <fcntl.h>       // O_RDWR / O_CREAT / O_TRUNC
#include <sys/stat.h>    // mode_t flags
#include <unistd.h>

namespace videoedit {

namespace {

constexpr const char* kVideoMime = "video/avc";

// COLOR_FormatYUV420Flexible — semi-private constant from MediaCodecInfo, value 2135033992.
// We pass this to the encoder format; AMediaCodec internally maps it to whichever
// concrete YUV420 layout the platform picks (semi-planar or planar). We then
// query the actual layout via AMediaCodec_getInputFormat after configure.
constexpr int kColorFormatYUV420Flexible = 0x7F420888;

// Drain timeout in microseconds when polling for encoder output. -1 (infinite)
// would block the encode thread on a slow GPU; 10 ms keeps the loop responsive.
constexpr int64_t kDrainTimeoutUs = 10'000;

} // namespace

VideoEncoder::~VideoEncoder() {
    if (codec_)  AMediaCodec_delete(codec_);
    if (muxer_)  AMediaMuxer_delete(muxer_);
    if (muxerFd_ >= 0) ::close(muxerFd_);
    if (swsCtx_) sws_freeContext(swsCtx_);
    if (yuvBuffer_) av_free(yuvBuffer_);
}

bool VideoEncoder::open(const std::string& outputPath, const EncoderConfig& config) {
    // ::open is the POSIX call (declared in <fcntl.h>); the leading :: is what
    // distinguishes it from this method.
    int fd = ::open(outputPath.c_str(), O_RDWR | O_CREAT | O_TRUNC,
                    static_cast<mode_t>(0644));
    if (fd < 0) {
        VE_LOGE("VideoEncoder: open(%s) failed", outputPath.c_str());
        return false;
    }
    if (!openFd(fd, config)) {
        ::close(fd);
        return false;
    }
    muxerFd_ = fd;
    return true;
}

bool VideoEncoder::openFd(int fd, const EncoderConfig& config) {
    config_ = config;
    muxer_ = AMediaMuxer_new(fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    if (!muxer_) {
        VE_LOGE("VideoEncoder: AMediaMuxer_new failed");
        return false;
    }
    AMediaMuxer_setOrientationHint(muxer_, config.rotation);
    return createEncoder(config);
}

bool VideoEncoder::createEncoder(const EncoderConfig& config) {
    AMediaFormat* fmt = AMediaFormat_new();
    AMediaFormat_setString(fmt, AMEDIAFORMAT_KEY_MIME, kVideoMime);
    AMediaFormat_setInt32(fmt,  AMEDIAFORMAT_KEY_WIDTH, config.width);
    AMediaFormat_setInt32(fmt,  AMEDIAFORMAT_KEY_HEIGHT, config.height);
    AMediaFormat_setInt32(fmt,  AMEDIAFORMAT_KEY_BIT_RATE, config.bitrateBps);
    AMediaFormat_setInt32(fmt,  AMEDIAFORMAT_KEY_FRAME_RATE, config.frameRate);
    AMediaFormat_setInt32(fmt,  AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, config.iFrameIntervalSec);
    AMediaFormat_setInt32(fmt,  AMEDIAFORMAT_KEY_COLOR_FORMAT, kColorFormatYUV420Flexible);

    codec_ = AMediaCodec_createEncoderByType(kVideoMime);
    if (!codec_) {
        VE_LOGE("AMediaCodec_createEncoderByType failed");
        AMediaFormat_delete(fmt);
        return false;
    }
    media_status_t st = AMediaCodec_configure(codec_, fmt, nullptr, nullptr,
                                              AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(fmt);
    if (st != AMEDIA_OK) {
        VE_LOGE("AMediaCodec_configure failed: %d", st);
        return false;
    }
    if (AMediaCodec_start(codec_) != AMEDIA_OK) {
        VE_LOGE("AMediaCodec_start failed");
        return false;
    }

    // Pre-allocate the YUV420P scratch buffer (planar Y + U + V) — used as the
    // sws output before we copy into the codec's input slot.
    yuvBufferSize_ = av_image_get_buffer_size(AV_PIX_FMT_YUV420P,
                                              config.width, config.height, 1);
    yuvBuffer_ = static_cast<uint8_t*>(av_malloc(yuvBufferSize_));
    if (!yuvBuffer_) {
        VE_LOGE("yuv buffer alloc failed");
        return false;
    }

    swsCtx_ = sws_getContext(
        config.width, config.height, AV_PIX_FMT_RGBA,
        config.width, config.height, AV_PIX_FMT_YUV420P,
        SWS_BILINEAR, nullptr, nullptr, nullptr
    );
    if (!swsCtx_) {
        VE_LOGE("sws_getContext failed");
        return false;
    }
    return true;
}

int VideoEncoder::addAudioPassthroughTrack(AMediaFormat* format) {
    if (!muxer_ || !format) return -1;
    audioTrackIdx_ = AMediaMuxer_addTrack(muxer_, format);
    if (audioTrackIdx_ < 0) VE_LOGE("addAudioPassthroughTrack failed: %zd", audioTrackIdx_);
    return audioTrackIdx_;
}

bool VideoEncoder::startMuxer() {
    if (!muxer_) return false;
    // Video track is added below — AMediaMuxer requires that you add tracks
    // *before* start, but the encoder's output format isn't known until the
    // first INFO_OUTPUT_FORMAT_CHANGED. We resolve this by calling a
    // bootstrap drain before start: queue zero-PTS dummy + drain.
    // Simpler approach: the editor pushes the first frame, drainEncoder lazily
    // adds the video track on INFO_OUTPUT_FORMAT_CHANGED, then starts the
    // muxer. So this method is effectively a no-op once the video track is
    // pinned; we just defer to the lazy path.
    muxerStarted_ = false;
    return true;
}

bool VideoEncoder::encodeFrame(const uint8_t* rgba, int64_t ptsUs) {
    if (!codec_ || !rgba) return false;

    // Convert RGBA → YUV420P into yuvBuffer_.
    uint8_t* yuvSlices[4] = {
        yuvBuffer_,
        yuvBuffer_ + config_.width * config_.height,
        yuvBuffer_ + config_.width * config_.height + (config_.width / 2) * (config_.height / 2),
        nullptr,
    };
    int yuvStrides[4] = {
        config_.width,
        config_.width / 2,
        config_.width / 2,
        0,
    };
    const uint8_t* rgbaSlices[4] = { rgba, nullptr, nullptr, nullptr };
    int            rgbaStrides[4] = { config_.width * 4, 0, 0, 0 };
    sws_scale(swsCtx_, rgbaSlices, rgbaStrides, 0, config_.height, yuvSlices, yuvStrides);

    // Queue into MediaCodec. Block up to 10 ms for an input buffer; if no slot
    // is ready, drain output once to make room and retry. This back-pressure
    // model keeps memory bounded on slow encoders.
    while (true) {
        ssize_t inputIdx = AMediaCodec_dequeueInputBuffer(codec_, kDrainTimeoutUs);
        if (inputIdx >= 0) {
            size_t bufSize = 0;
            uint8_t* inputBuf = AMediaCodec_getInputBuffer(codec_, inputIdx, &bufSize);
            if (!inputBuf || bufSize < static_cast<size_t>(yuvBufferSize_)) {
                VE_LOGE("Encoder input buffer too small: %zu < %d", bufSize, yuvBufferSize_);
                return false;
            }
            std::memcpy(inputBuf, yuvBuffer_, yuvBufferSize_);
            media_status_t st = AMediaCodec_queueInputBuffer(
                codec_, inputIdx, 0, yuvBufferSize_, ptsUs, 0);
            if (st != AMEDIA_OK) {
                VE_LOGE("queueInputBuffer failed: %d", st);
                return false;
            }
            lastVideoPtsUs_ = ptsUs;
            break;
        }
        // No input slot available — drain output and try again.
        if (!drainEncoder(false)) return false;
    }
    return drainEncoder(false);
}

bool VideoEncoder::drainEncoder(bool endOfStream) {
    if (!codec_) return false;

    if (endOfStream) {
        ssize_t inputIdx = AMediaCodec_dequeueInputBuffer(codec_, -1);
        if (inputIdx >= 0) {
            AMediaCodec_queueInputBuffer(codec_, inputIdx, 0, 0, lastVideoPtsUs_,
                                         AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
        }
    }

    while (true) {
        AMediaCodecBufferInfo info;
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec_, &info, kDrainTimeoutUs);
        if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER) {
            if (!endOfStream) return true;            // come back later
            continue;                                  // EOS — keep draining
        }
        if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            // First real output → encoder finalised its format. Add the video
            // track and start the muxer if it isn't already running.
            AMediaFormat* outFmt = AMediaCodec_getOutputFormat(codec_);
            videoTrackIdx_ = AMediaMuxer_addTrack(muxer_, outFmt);
            AMediaFormat_delete(outFmt);
            if (videoTrackIdx_ < 0) {
                VE_LOGE("addTrack(video) failed: %d", videoTrackIdx_);
                return false;
            }
            if (!muxerStarted_) {
                if (AMediaMuxer_start(muxer_) != AMEDIA_OK) {
                    VE_LOGE("AMediaMuxer_start failed");
                    return false;
                }
                muxerStarted_ = true;
            }
            continue;
        }
        if (outIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED) continue;
        if (outIdx < 0) {
            VE_LOGE("dequeueOutputBuffer error: %zd", outIdx);
            return false;
        }

        size_t bufSize = 0;
        uint8_t* buf = AMediaCodec_getOutputBuffer(codec_, outIdx, &bufSize);
        if (buf && info.size > 0 && muxerStarted_ && videoTrackIdx_ >= 0) {
            // Codec config blob is signalled with BUFFER_FLAG_CODEC_CONFIG; the
            // muxer absorbs it into the track header automatically when we
            // pass the same buffer to writeSampleData with the same flags.
            AMediaMuxer_writeSampleData(muxer_, videoTrackIdx_, buf, &info);
        }
        AMediaCodec_releaseOutputBuffer(codec_, outIdx, false);

        if ((info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) return true;
    }
}

bool VideoEncoder::writeAudioSample(const uint8_t* data, size_t size,
                                    int64_t ptsUs, uint32_t flags) {
    if (!muxer_ || !muxerStarted_ || audioTrackIdx_ < 0 || !data || size == 0) return false;
    AMediaCodecBufferInfo info{};
    info.offset = 0;
    info.size   = static_cast<int32_t>(size);
    info.presentationTimeUs = ptsUs;
    info.flags  = flags;
    AMediaMuxer_writeSampleData(muxer_, audioTrackIdx_,
                                const_cast<uint8_t*>(data), &info);
    return true;
}

bool VideoEncoder::finish() {
    bool ok = drainEncoder(true);
    if (codec_) {
        AMediaCodec_stop(codec_);
        AMediaCodec_delete(codec_);
        codec_ = nullptr;
    }
    if (muxer_) {
        if (muxerStarted_) AMediaMuxer_stop(muxer_);
        AMediaMuxer_delete(muxer_);
        muxer_ = nullptr;
    }
    if (muxerFd_ >= 0) {
        ::close(muxerFd_);
        muxerFd_ = -1;
    }
    return ok;
}

} // namespace videoedit
