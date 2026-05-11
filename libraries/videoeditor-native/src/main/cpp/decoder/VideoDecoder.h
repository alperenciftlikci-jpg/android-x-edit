// Copyright (c) 2026 Element Creations Ltd.
// SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
//
// FFmpeg-backed video decoder. Opens a file, walks packets, decodes the video
// stream and converts each frame to RGBA8 in a caller-supplied buffer.
//
// We deliberately keep audio out of this header — for the editor we ingest
// audio through a separate decoder and re-encode it; mixing the two state
// machines into a single class made the cleanup paths confusing in earlier
// drafts.
#pragma once

#include <cstdint>
#include <string>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswscale/swscale.h>
}

namespace videoedit {

struct VideoMetadata {
    int      width      = 0;
    int      height     = 0;
    int64_t  durationUs = 0;       // total duration in microseconds (AV_TIME_BASE)
    double   fps        = 0.0;     // average frame rate
    int      rotation   = 0;       // 0 / 90 / 180 / 270 (from display matrix sidedata)
    bool     hasAudio   = false;
    AVCodecID videoCodec = AV_CODEC_ID_NONE;
};

class VideoDecoder {
public:
    VideoDecoder() = default;
    ~VideoDecoder();
    VideoDecoder(const VideoDecoder&) = delete;
    VideoDecoder& operator=(const VideoDecoder&) = delete;

    /**
     * Open the file at [path]. Path is a file system path — for content://
     * URIs callers should pre-resolve via ContentResolver.openFileDescriptor and
     * pass the file descriptor through `openFd` instead. Returns false on any
     * FFmpeg error; the object can be reused (call again with a different
     * path) only after `close()`.
     */
    bool open(const std::string& path);

    /**
     * Same as `open()` but takes a file descriptor (for content:// URIs which
     * Android resolves to fd via `ContentResolver.openFileDescriptor`). The fd
     * is duped internally so the caller can close their original fd at any time.
     */
    bool openFd(int fd);

    void close();

    const VideoMetadata& metadata() const { return metadata_; }
    bool isOpen() const { return formatCtx_ != nullptr; }

    /**
     * Decode the next frame. Output goes into [outRgba], which must be at least
     * `metadata().width * metadata().height * 4` bytes. `*outPtsUs` receives the
     * frame's presentation timestamp in microseconds, or AV_NOPTS_VALUE if the
     * stream doesn't carry timestamps.
     *
     * Return values:
     *   1  — frame produced (outRgba filled)
     *   0  — end-of-stream
     *  -1  — error (logged)
     */
    int decodeNextFrame(uint8_t* outRgba, int64_t* outPtsUs);

    /**
     * Seek to the given microsecond timestamp. After seek, the next
     * `decodeNextFrame` call returns the keyframe at-or-before [timeUs] —
     * caller is responsible for skipping forward to the exact target frame
     * if frame-accurate seeking is required.
     */
    bool seekTo(int64_t timeUs);

private:
    bool initStreams();
    void freeStreams();

    AVFormatContext* formatCtx_     = nullptr;
    AVCodecContext*  videoCodecCtx_ = nullptr;
    AVFrame*         frame_         = nullptr;
    AVFrame*         rgbaFrame_     = nullptr;        // sws output
    AVPacket*        packet_        = nullptr;
    SwsContext*      swsCtx_        = nullptr;

    int videoStreamIdx_ = -1;
    int audioStreamIdx_ = -1;
    VideoMetadata metadata_{};
};

} // namespace videoedit
