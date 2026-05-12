// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
#include "StreamCopyTrim.h"
#include "../core/Logger.h"

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/mathematics.h>
#include <libavutil/error.h>
}

#include <algorithm>
#include <vector>

namespace videoedit {

namespace {

// Pretty-print an FFmpeg error code for our logs.
std::string avErrorString(int err) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(err, buf, sizeof(buf));
    return std::string(buf);
}

} // namespace

StreamCopyTrimResult streamCopyTrim(
    const char* inputPath,
    const char* outputPath,
    int64_t startUs,
    int64_t endUs,
    const TrimProgressCallback& progress
) {
    StreamCopyTrimResult result;
    AVFormatContext* inputCtx  = nullptr;
    AVFormatContext* outputCtx = nullptr;
    AVPacket*        pkt       = nullptr;
    // Forward declarations so any `goto cleanup` doesn't bypass their initialization
    // (illegal in C++). Filled in once inputCtx is opened and stream count is known.
    std::vector<int> streamMapping;
    int outIdx = 0;

    VE_LOGI("streamCopyTrim: in=%s out=%s start=%lld end=%lld",
            inputPath, outputPath, (long long)startUs, (long long)endUs);

    // ---- Open input ----------------------------------------------------------
    int err = avformat_open_input(&inputCtx, inputPath, nullptr, nullptr);
    if (err < 0) {
        result.error = "avformat_open_input: " + avErrorString(err);
        VE_LOGE("%s", result.error.c_str());
        goto cleanup;
    }
    err = avformat_find_stream_info(inputCtx, nullptr);
    if (err < 0) {
        result.error = "avformat_find_stream_info: " + avErrorString(err);
        VE_LOGE("%s", result.error.c_str());
        goto cleanup;
    }
    VE_LOGI("streamCopyTrim: input has %u streams, duration=%lld us",
            inputCtx->nb_streams, (long long)inputCtx->duration);

    // ---- Allocate output (MP4) -----------------------------------------------
    err = avformat_alloc_output_context2(&outputCtx, nullptr, "mp4", outputPath);
    if (err < 0 || !outputCtx) {
        result.error = "avformat_alloc_output_context2: " + avErrorString(err);
        VE_LOGE("%s", result.error.c_str());
        goto cleanup;
    }

    // ---- Map streams 1:1 — copy codec params, no encoder ---------------------
    streamMapping.assign(inputCtx->nb_streams, -1);
    for (unsigned i = 0; i < inputCtx->nb_streams; i++) {
        AVStream* inStream = inputCtx->streams[i];
        const AVMediaType t = inStream->codecpar->codec_type;
        if (t != AVMEDIA_TYPE_VIDEO && t != AVMEDIA_TYPE_AUDIO) {
            continue;
        }
        AVStream* outStream = avformat_new_stream(outputCtx, nullptr);
        if (!outStream) {
            result.error = "avformat_new_stream returned null";
            VE_LOGE("%s", result.error.c_str());
            goto cleanup;
        }
        err = avcodec_parameters_copy(outStream->codecpar, inStream->codecpar);
        if (err < 0) {
            result.error = "avcodec_parameters_copy: " + avErrorString(err);
            VE_LOGE("%s", result.error.c_str());
            goto cleanup;
        }
        // codec_tag=0 lets the muxer pick the appropriate one for the container.
        outStream->codecpar->codec_tag = 0;
        // Initialize time_base from the source so av_packet_rescale_ts works correctly.
        outStream->time_base = inStream->time_base;
        streamMapping[i] = outIdx++;
        VE_LOGI("streamCopyTrim: map src[%u] type=%d → dst[%d]", i, t, outIdx - 1);
    }
    if (outIdx == 0) {
        result.error = "no video/audio streams in input";
        VE_LOGE("%s", result.error.c_str());
        goto cleanup;
    }

    // ---- Open output file ----------------------------------------------------
    if (!(outputCtx->oformat->flags & AVFMT_NOFILE)) {
        err = avio_open(&outputCtx->pb, outputPath, AVIO_FLAG_WRITE);
        if (err < 0) {
            result.error = "avio_open: " + avErrorString(err);
            VE_LOGE("%s", result.error.c_str());
            goto cleanup;
        }
    }

    // ---- Write container header ----------------------------------------------
    err = avformat_write_header(outputCtx, nullptr);
    if (err < 0) {
        result.error = "avformat_write_header: " + avErrorString(err);
        VE_LOGE("%s", result.error.c_str());
        goto cleanup;
    }

    // ---- Seek to start (previous keyframe) -----------------------------------
    // av_seek_frame's timestamp is in AV_TIME_BASE units when stream_index=-1.
    if (startUs > 0) {
        err = av_seek_frame(inputCtx, -1, startUs, AVSEEK_FLAG_BACKWARD);
        if (err < 0) {
            VE_LOGW("av_seek_frame failed (%s) — continuing from current position",
                    avErrorString(err).c_str());
        }
    }

    // ---- Copy packets --------------------------------------------------------
    pkt = av_packet_alloc();
    if (!pkt) {
        result.error = "av_packet_alloc returned null";
        VE_LOGE("%s", result.error.c_str());
        goto cleanup;
    }

    {
        int64_t baseUs    = AV_NOPTS_VALUE;   // pts of the first written packet (any stream)
        int64_t lastPtsUs = 0;
        int     packets   = 0;
        int     progressTick = 0;

        while (true) {
            err = av_read_frame(inputCtx, pkt);
            if (err == AVERROR_EOF) break;
            if (err < 0) {
                VE_LOGW("av_read_frame: %s — stopping", avErrorString(err).c_str());
                break;
            }

            const int srcIdx = pkt->stream_index;
            if (srcIdx < 0 || srcIdx >= (int)streamMapping.size() ||
                streamMapping[srcIdx] < 0) {
                av_packet_unref(pkt);
                continue;
            }

            AVStream* inStream  = inputCtx->streams[srcIdx];
            AVStream* outStream = outputCtx->streams[streamMapping[srcIdx]];

            // Packet pts in microseconds for the trim-window check.
            const int64_t pktPtsUs = (pkt->pts == AV_NOPTS_VALUE)
                ? AV_NOPTS_VALUE
                : av_rescale_q(pkt->pts, inStream->time_base, AV_TIME_BASE_Q);

            if (endUs > 0 && pktPtsUs != AV_NOPTS_VALUE && pktPtsUs >= endUs) {
                av_packet_unref(pkt);
                // Note: ideally we'd only stop when ALL streams have passed endUs, but
                // for fast-trim the slight A/V tail mismatch is acceptable (and matches
                // Telegram's behavior).
                break;
            }

            if (baseUs == AV_NOPTS_VALUE && pktPtsUs != AV_NOPTS_VALUE) {
                baseUs = pktPtsUs;
            }
            if (pktPtsUs != AV_NOPTS_VALUE) {
                lastPtsUs = pktPtsUs;
            }

            // Rescale timestamps from input → output timebase. pos=-1 lets the muxer
            // compute the offset itself.
            av_packet_rescale_ts(pkt, inStream->time_base, outStream->time_base);
            pkt->stream_index = streamMapping[srcIdx];
            pkt->pos = -1;

            err = av_interleaved_write_frame(outputCtx, pkt);
            // av_interleaved_write_frame takes ownership; unref before next read.
            av_packet_unref(pkt);
            if (err < 0) {
                result.error = "av_interleaved_write_frame: " + avErrorString(err);
                VE_LOGE("%s", result.error.c_str());
                goto cleanup;
            }

            packets++;
            // Progress every 30 packets to keep JNI calls reasonable.
            if (progress && ++progressTick >= 30) {
                progressTick = 0;
                if (pktPtsUs != AV_NOPTS_VALUE && endUs > startUs) {
                    const float fraction = (float)(pktPtsUs - startUs) /
                                           (float)(endUs - startUs);
                    progress(std::clamp(fraction, 0.0f, 1.0f));
                }
            }
        }

        // ---- Write trailer (moov atom for MP4) -------------------------------
        err = av_write_trailer(outputCtx);
        if (err < 0) {
            result.error = "av_write_trailer: " + avErrorString(err);
            VE_LOGE("%s", result.error.c_str());
            goto cleanup;
        }

        result.bytesWritten   = outputCtx->pb ? avio_tell(outputCtx->pb) : 0;
        result.packetsWritten = packets;
        result.firstPtsUs     = baseUs != AV_NOPTS_VALUE ? baseUs : 0;
        result.lastPtsUs      = lastPtsUs;
        result.success        = true;
        if (progress) progress(1.0f);

        VE_LOGI("streamCopyTrim: done packets=%d bytes=%lld firstPts=%lld lastPts=%lld",
                packets, (long long)result.bytesWritten,
                (long long)result.firstPtsUs, (long long)result.lastPtsUs);
    }

cleanup:
    if (pkt) av_packet_free(&pkt);
    if (inputCtx) avformat_close_input(&inputCtx);
    if (outputCtx) {
        if (outputCtx->pb && !(outputCtx->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&outputCtx->pb);
        }
        avformat_free_context(outputCtx);
    }
    return result;
}

} // namespace videoedit
