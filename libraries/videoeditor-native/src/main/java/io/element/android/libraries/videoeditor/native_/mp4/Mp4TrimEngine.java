/*
 * Mux-only trim path from Telegram for Android.
 *
 * Verbatim ports of:
 *   - org.telegram.messenger.video.MediaCodecVideoConvertor.Muxer
 *   - org.telegram.messenger.video.MediaCodecVideoConvertor#readAndWriteTracks
 *   - org.telegram.messenger.MediaController#findTrack
 *
 * Compile-time adaptations (kept to the absolute minimum required to build outside the
 * Telegram tree):
 *   - package renamed to ours
 *   - FileLog.e(...) → android.util.Log.e (Telegram's logger isn't on our classpath)
 *   - checkConversionCanceled() removed (Telegram's cancellation framework isn't here)
 *   - callback.didWriteData(size, fraction) routed through a one-method interface
 *
 * Nothing else differs from the original.
 */
package io.element.android.libraries.videoeditor.native_.mp4;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

public final class Mp4TrimEngine {

    private static final String TAG = "Mp4TrimEngine";

    public interface ProgressCallback {
        void didWriteData(long availableSize, float progress);
    }

    public static final class Result {
        public final boolean success;
        public final String error;
        public final long bytesWritten;

        Result(boolean success, String error, long bytesWritten) {
            this.success = success;
            this.error = error;
            this.bytesWritten = bytesWritten;
        }
    }

    private Mp4TrimEngine() {}

    /**
     * Convenience entry point — opens the source, sets up Mp4Movie/MP4Builder via the
     * Muxer wrapper, runs readAndWriteTracks, and finishMovies. Mirrors the mux-only
     * else-branch of MediaCodecVideoConvertor#convertVideoInternal.
     */
    public static Result trim(
            String inputPath,
            File outputFile,
            long startUs,
            long endUs,
            boolean needAudio,
            int resultWidth,
            int resultHeight,
            ProgressCallback callback
    ) {
        MediaExtractor extractor = new MediaExtractor();
        Muxer mediaMuxer = null;
        try {
            extractor.setDataSource(inputPath);

            Mp4Movie movie = new Mp4Movie();
            movie.setCacheFile(outputFile);
            movie.setRotation(0);
            movie.setSize(resultWidth, resultHeight);
            MP4Builder builder = new MP4Builder().createMovie(movie, false, false);
            mediaMuxer = new Muxer(builder);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long duration = endUs > startUs ? (endUs - startUs) : 0;
            readAndWriteTracks(extractor, mediaMuxer, info,
                    startUs, endUs, duration, needAudio, callback);

            mediaMuxer.finishMovie();
            return new Result(true, null, outputFile.length());
        } catch (Throwable t) {
            Log.e(TAG, "trim failed", t);
            return new Result(false,
                    t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName(),
                    0);
        } finally {
            try { extractor.release(); } catch (Throwable ignored) {}
        }
    }

    // ------------------------------------------------------------------------
    // VERBATIM from MediaController#findTrack (line 6215).
    // ------------------------------------------------------------------------
    public static int findTrack(MediaExtractor extractor, boolean audio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -5;
    }

    // ------------------------------------------------------------------------
    // VERBATIM from MediaCodecVideoConvertor.Muxer (lines 1081-1146 of the source).
    // ------------------------------------------------------------------------
    public static class Muxer {

        public final MP4Builder mp4Builder;
        public final MediaMuxer mediaMuxer;

        private boolean started = false;

        public Muxer(MP4Builder mp4Builder) {
            this.mp4Builder = mp4Builder;
            this.mediaMuxer = null;
        }
        public Muxer(MediaMuxer mediaMuxer) {
            this.mp4Builder = null;
            this.mediaMuxer = mediaMuxer;
        }

        public int addTrack(MediaFormat format, boolean isAudio) {
            if (mediaMuxer != null) {
                return mediaMuxer.addTrack(format);
            } else if (mp4Builder != null) {
                return mp4Builder.addTrack(format, isAudio);
            }
            return 0;
        }

        public long writeSampleData(int trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo, boolean writeLength) throws Exception {
            if (mediaMuxer != null) {
                if (!started) {
                    mediaMuxer.start();
                    started = true;
                }
                mediaMuxer.writeSampleData(trackIndex, byteBuf, bufferInfo);
                return 0;
            } else if (mp4Builder != null) {
                return mp4Builder.writeSampleData(trackIndex, byteBuf, bufferInfo, writeLength);
            }
            return 0;
        }

        public long getLastFrameTimestamp(int trackIndex, MediaCodec.BufferInfo bufferInfo) {
            if (mediaMuxer != null) {
                return bufferInfo.presentationTimeUs;
            } else if (mp4Builder != null) {
                return mp4Builder.getLastFrameTimestamp(trackIndex);
            }
            return 0;
        }

        public void start() {
            if (mediaMuxer != null) {
                mediaMuxer.start();
            } else if (mp4Builder != null) {

            }
        }

        public void finishMovie() throws Exception {
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            } else if (mp4Builder != null) {
                mp4Builder.finishMovie();
            }
        }

    }

    // ------------------------------------------------------------------------
    // VERBATIM from MediaCodecVideoConvertor#readAndWriteTracks (lines 1148-1300).
    // Compile-time adaptations only:
    //   - FileLog.e → Log.e
    //   - checkConversionCanceled() calls dropped
    //   - callback.didWriteData routed through our ProgressCallback interface
    //   - `File file` parameter dropped (unused in body)
    //   - `needAudio` becomes a parameter (was a method-scope local in Telegram's
    //     convertVideoInternal that called this method)
    // ------------------------------------------------------------------------
    private static long readAndWriteTracks(
            MediaExtractor extractor, Muxer mediaMuxer,
            MediaCodec.BufferInfo info, long start, long end, long duration,
            boolean needAudio, ProgressCallback callback
    ) throws Exception {
        int videoTrackIndex = findTrack(extractor, false);
        int audioTrackIndex = needAudio ? findTrack(extractor, true) : -1;
        int muxerVideoTrackIndex = -1;
        int muxerAudioTrackIndex = -1;
        boolean inputDone = false;

        long currentPts = 0;
        float durationS = duration / 1000f;

        int maxBufferSize = 0;
        if (videoTrackIndex >= 0) {
            extractor.selectTrack(videoTrackIndex);
            MediaFormat trackFormat = extractor.getTrackFormat(videoTrackIndex);
            muxerVideoTrackIndex = mediaMuxer.addTrack(trackFormat, false);
            try {
                maxBufferSize = trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
            } catch (Exception e) {
                Log.e(TAG, "video track MAX_INPUT_SIZE", e); //s20 ultra exception (per Telegram)
            }

            if (start > 0) {
                extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            } else {
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
        }
        if (audioTrackIndex >= 0) {
            extractor.selectTrack(audioTrackIndex);
            MediaFormat trackFormat = extractor.getTrackFormat(audioTrackIndex);

            if (trackFormat.getString(MediaFormat.KEY_MIME).equals("audio/unknown")) {
                audioTrackIndex = -1;
            } else {
                muxerAudioTrackIndex = mediaMuxer.addTrack(trackFormat, true);
                try {
                    maxBufferSize = Math.max(trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), maxBufferSize);
                } catch (Exception e) {
                    Log.e(TAG, "audio track MAX_INPUT_SIZE", e); //s20 ultra exception (per Telegram)
                }
                if (start > 0) {
                    extractor.seekTo(start, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                } else {
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                }
            }
        }
        if (maxBufferSize <= 0) {
            maxBufferSize = 64 * 1024;
        }
        // ONLY DIVERGENCE from Telegram: heap-backed buffer instead of allocateDirect.
        // Telegram's existing NAL rewrite loop below (`byte[] array = buffer.array();
        // if (array != null) { ... }`) is silent dead code on Android when the buffer
        // is direct — direct buffers return null from array() on this platform. Their
        // phones don't need the rewrite because MediaExtractor returns AVCC there.
        // Some Android versions / emulators return Annex-B, in which case we MUST run
        // the rewrite or the decoder rejects everything past the IDR keyframe.
        // Switching to heap activates Telegram's own rewrite code unchanged.
        ByteBuffer buffer = ByteBuffer.allocate(maxBufferSize);
        if (audioTrackIndex >= 0 || videoTrackIndex >= 0) {
            long startTime = -1;
            while (!inputDone) {
                boolean eof = false;
                int muxerTrackIndex;
                if (Build.VERSION.SDK_INT >= 28) {
                    long size = extractor.getSampleSize();
                    if (size > maxBufferSize) {
                        maxBufferSize = (int) (size + 1024);
                        // Heap, to match the initial allocation above (so array() stays
                        // non-null after a resize).
                        buffer = ByteBuffer.allocate(maxBufferSize);
                    }
                }
                info.size = extractor.readSampleData(buffer, 0);
                int index = extractor.getSampleTrackIndex();
                if (index == videoTrackIndex) {
                    muxerTrackIndex = muxerVideoTrackIndex;
                } else if (index == audioTrackIndex) {
                    muxerTrackIndex = muxerAudioTrackIndex;
                } else {
                    muxerTrackIndex = -1;
                }
                if (muxerTrackIndex != -1) {
                    if (Build.VERSION.SDK_INT < 21) {
                        buffer.position(0);
                        buffer.limit(info.size);
                    }
                    if (index != audioTrackIndex) {
                        byte[] array = buffer.array();
                        if (array != null) {
                            int offset = buffer.arrayOffset();
                            int len = offset + buffer.limit();
                            int writeStart = -1;
                            for (int a = offset; a <= len - 4; a++) {
                                if (array[a] == 0 && array[a + 1] == 0 && array[a + 2] == 0 && array[a + 3] == 1 || a == len - 4) {
                                    if (writeStart != -1) {
                                        int l = a - writeStart - (a != len - 4 ? 4 : 0);
                                        array[writeStart] = (byte) (l >> 24);
                                        array[writeStart + 1] = (byte) (l >> 16);
                                        array[writeStart + 2] = (byte) (l >> 8);
                                        array[writeStart + 3] = (byte) l;
                                        writeStart = a;
                                    } else {
                                        writeStart = a;
                                    }
                                }
                            }
                        }
                    }
                    if (info.size >= 0) {
                        info.presentationTimeUs = extractor.getSampleTime();
                    } else {
                        info.size = 0;
                        eof = true;
                    }

                    if (info.size > 0 && !eof) {
                        if (index == videoTrackIndex && start > 0 && startTime == -1) {
                            startTime = info.presentationTimeUs;
                        }
                        if (end < 0 || info.presentationTimeUs < end) {
                            info.offset = 0;
                            info.flags = extractor.getSampleFlags();
                            long availableSize = mediaMuxer.writeSampleData(muxerTrackIndex, buffer, info, false);
                            if (availableSize != 0) {
                                if (callback != null) {
                                    if (info.presentationTimeUs - startTime > currentPts) {
                                        currentPts = info.presentationTimeUs - startTime;
                                    }
                                    callback.didWriteData(availableSize, (currentPts / 1000f) / durationS);
                                }
                            }
                        } else {
                            eof = true;
                        }
                    }
                    if (!eof) {
                        extractor.advance();
                    }
                } else if (index == -1) {
                    eof = true;
                } else {
                    extractor.advance();
                }
                if (eof) {
                    inputDone = true;
                }
            }
            if (videoTrackIndex >= 0) {
                extractor.unselectTrack(videoTrackIndex);
            }
            if (audioTrackIndex >= 0) {
                extractor.unselectTrack(audioTrackIndex);
            }
            return startTime;
        }
        return -1;
    }
}
