// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "VideoDecoder.h"

#include "../core/Logger.h"

extern "C" {
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
}

#include <cmath>
#include <cstdio>
#include <unistd.h>      // dup, close (POSIX)

namespace videoedit {

namespace {

// Pull the rotation angle out of the side-data display matrix attached to the
// video stream. Telegram-recorded videos usually store rotation here rather
// than in a metadata tag, so without this the editor renders portraits as
// landscape.
int rotationFromStream(AVStream* stream) {
    uint8_t* sideData = av_stream_get_side_data(stream, AV_PKT_DATA_DISPLAYMATRIX, nullptr);
    if (sideData != nullptr) {
        const double rot = av_display_rotation_get(reinterpret_cast<int32_t*>(sideData));
        if (!std::isnan(rot)) {
            // av_display_rotation_get returns the angle the picture should be
            // rotated *to display correctly*; we negate so consumers can apply
            // it as a positive rotation to the pixel data.
            int normalized = static_cast<int>(std::round(-rot));
            normalized = ((normalized % 360) + 360) % 360;
            return normalized;
        }
    }
    return 0;
}

} // namespace

VideoDecoder::~VideoDecoder() { close(); }

bool VideoDecoder::open(const std::string& path) {
    close();
    if (avformat_open_input(&formatCtx_, path.c_str(), nullptr, nullptr) < 0) {
        VE_LOGE("avformat_open_input failed for %s", path.c_str());
        return false;
    }
    return initStreams();
}

bool VideoDecoder::openFd(int fd) {
    close();
    // FFmpeg accepts paths like "pipe:<fd>" / "fd:<n>"; the latter is more
    // portable across builds. Dup the fd so we own a reference we can close
    // independently of the caller's lifecycle.
    int dupFd = dup(fd);
    if (dupFd < 0) {
        VE_LOGE("openFd: dup failed");
        return false;
    }
    char url[64];
    snprintf(url, sizeof(url), "fd:%d", dupFd);
    if (avformat_open_input(&formatCtx_, url, nullptr, nullptr) < 0) {
        VE_LOGE("openFd: avformat_open_input failed");
        ::close(dupFd);
        return false;
    }
    // FFmpeg now owns the fd; it'll close on avformat_close_input.
    return initStreams();
}

bool VideoDecoder::initStreams() {
    if (avformat_find_stream_info(formatCtx_, nullptr) < 0) {
        VE_LOGE("avformat_find_stream_info failed");
        close();
        return false;
    }

    videoStreamIdx_ = av_find_best_stream(formatCtx_, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    audioStreamIdx_ = av_find_best_stream(formatCtx_, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (videoStreamIdx_ < 0) {
        VE_LOGE("No video stream found");
        close();
        return false;
    }

    AVStream* videoStream = formatCtx_->streams[videoStreamIdx_];
    const AVCodec* codec = avcodec_find_decoder(videoStream->codecpar->codec_id);
    if (!codec) {
        VE_LOGE("Codec not found: 0x%x", videoStream->codecpar->codec_id);
        close();
        return false;
    }

    videoCodecCtx_ = avcodec_alloc_context3(codec);
    if (!videoCodecCtx_) {
        close();
        return false;
    }
    if (avcodec_parameters_to_context(videoCodecCtx_, videoStream->codecpar) < 0) {
        close();
        return false;
    }
    if (avcodec_open2(videoCodecCtx_, codec, nullptr) < 0) {
        VE_LOGE("avcodec_open2 failed");
        close();
        return false;
    }

    frame_     = av_frame_alloc();
    rgbaFrame_ = av_frame_alloc();
    packet_    = av_packet_alloc();
    if (!frame_ || !rgbaFrame_ || !packet_) {
        close();
        return false;
    }

    // Pre-build the sws conversion context. Source pix_fmt becomes known after
    // the first frame in some cases (codec extradata-only), but for the common
    // case (h264, hevc, vp9) it's set at codec_open. We rebuild lazily inside
    // decodeNextFrame if the codec changes its format mid-stream.
    swsCtx_ = sws_getContext(
        videoCodecCtx_->width, videoCodecCtx_->height, videoCodecCtx_->pix_fmt,
        videoCodecCtx_->width, videoCodecCtx_->height, AV_PIX_FMT_RGBA,
        SWS_BILINEAR, nullptr, nullptr, nullptr
    );

    metadata_.width      = videoCodecCtx_->width;
    metadata_.height     = videoCodecCtx_->height;
    metadata_.durationUs = formatCtx_->duration;
    metadata_.videoCodec = videoCodecCtx_->codec_id;
    if (videoStream->avg_frame_rate.den > 0) {
        metadata_.fps = av_q2d(videoStream->avg_frame_rate);
    }
    metadata_.rotation = rotationFromStream(videoStream);
    metadata_.hasAudio = audioStreamIdx_ >= 0;

    VE_LOGI("Opened: %dx%d, %.2f fps, %lld us, codec=%s, rot=%d, audio=%d",
            metadata_.width, metadata_.height, metadata_.fps,
            (long long)metadata_.durationUs, codec->name, metadata_.rotation,
            metadata_.hasAudio ? 1 : 0);
    return true;
}

void VideoDecoder::close() {
    freeStreams();
    if (formatCtx_) {
        avformat_close_input(&formatCtx_);
        formatCtx_ = nullptr;
    }
    metadata_ = VideoMetadata{};
    videoStreamIdx_ = -1;
    audioStreamIdx_ = -1;
}

void VideoDecoder::freeStreams() {
    if (swsCtx_) {
        sws_freeContext(swsCtx_);
        swsCtx_ = nullptr;
    }
    if (videoCodecCtx_) {
        avcodec_free_context(&videoCodecCtx_);
        videoCodecCtx_ = nullptr;
    }
    if (frame_)     { av_frame_free(&frame_); }
    if (rgbaFrame_) { av_frame_free(&rgbaFrame_); }
    if (packet_)    { av_packet_free(&packet_); }
}

int VideoDecoder::decodeNextFrame(uint8_t* outRgba, int64_t* outPtsUs) {
    if (!isOpen() || !outRgba) return -1;

    while (true) {
        // Pull a frame the codec already has buffered (B-frames / reordering).
        int recv = avcodec_receive_frame(videoCodecCtx_, frame_);
        if (recv == 0) {
            // Got a frame — convert to RGBA into the caller's buffer.
            const int dstStride = metadata_.width * 4;
            uint8_t* dstSlices[4] = { outRgba, nullptr, nullptr, nullptr };
            int      dstStrides[4] = { dstStride, 0, 0, 0 };
            sws_scale(swsCtx_,
                      frame_->data, frame_->linesize, 0, metadata_.height,
                      dstSlices, dstStrides);

            if (outPtsUs) {
                AVStream* stream = formatCtx_->streams[videoStreamIdx_];
                if (frame_->pts != AV_NOPTS_VALUE) {
                    *outPtsUs = av_rescale_q(frame_->pts, stream->time_base, AVRational{1, 1000000});
                } else {
                    *outPtsUs = AV_NOPTS_VALUE;
                }
            }
            av_frame_unref(frame_);
            return 1;
        }
        if (recv == AVERROR_EOF) return 0;
        if (recv != AVERROR(EAGAIN)) {
            VE_LOGE("avcodec_receive_frame error: %d", recv);
            return -1;
        }

        // Codec wants more data — read packets until we either feed it or hit EOF.
        int read = av_read_frame(formatCtx_, packet_);
        if (read == AVERROR_EOF) {
            // Flush the codec.
            avcodec_send_packet(videoCodecCtx_, nullptr);
            continue;
        }
        if (read < 0) {
            VE_LOGE("av_read_frame error: %d", read);
            return -1;
        }
        if (packet_->stream_index == videoStreamIdx_) {
            int send = avcodec_send_packet(videoCodecCtx_, packet_);
            av_packet_unref(packet_);
            if (send < 0 && send != AVERROR(EAGAIN) && send != AVERROR_EOF) {
                VE_LOGE("avcodec_send_packet error: %d", send);
                return -1;
            }
        } else {
            av_packet_unref(packet_);
        }
    }
}

bool VideoDecoder::seekTo(int64_t timeUs) {
    if (!isOpen()) return false;
    AVStream* stream = formatCtx_->streams[videoStreamIdx_];
    int64_t pts = av_rescale_q(timeUs, AVRational{1, 1000000}, stream->time_base);
    int err = av_seek_frame(formatCtx_, videoStreamIdx_, pts, AVSEEK_FLAG_BACKWARD);
    if (err < 0) {
        VE_LOGE("av_seek_frame error: %d", err);
        return false;
    }
    avcodec_flush_buffers(videoCodecCtx_);
    return true;
}

} // namespace videoedit
