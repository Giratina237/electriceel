package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;

/**
 * Whether a live stream's segments are gone: both its tracks answer HTTP 404, and neither has loaded a segment since its first one,
 * for GONE_MS. The stream ended, or its URLs aren't served anymore: asked again, they answer 404 again, and a reload asks for the same
 * ones (FVNAAYZD: a stream that stopped at 1:00:01 answered 404 on both tracks for two minutes, through three stall reloads). A 404
 * that a load follows was the live edge's: its newest segment wasn't there yet (see TrackErrorFixer).<br/>
 * Of one video: another one's 404 starts over. Told on the main thread, as the player's events are.
 */
public final class LiveSegmentsGone {
    /**
     * Far longer than the live edge keeps a segment waiting: a segment is 1 to 5 s of the stream, and a 404 is tried again within 5 s
     * (see DashDefaultLoadErrorHandlingPolicy)
     */
    static final long GONE_MS = 15_000;
    /**
     * A 404 this long after the one before starts over: those were of a playback before (e.g. the video opened again later). The
     * segments gone answer 404 more often, a reload's wait included.
     */
    static final long STALE_MS = 60_000;
    @Nullable
    private String mVideoId;
    private long mVideoNotFoundMs = -1;
    private long mAudioNotFoundMs = -1;
    private long mLastNotFoundMs = -1;

    /**
     * A segment of the track answered 404
     *
     * @param nowMs e.g. SystemClock#elapsedRealtime
     * @return how long the segments of both tracks have been gone (see the class), -1 when they aren't
     */
    public long onNotFound(@Nullable String videoId, boolean isVideo, long nowMs) {
        if (!Helpers.equals(videoId, mVideoId) || mLastNotFoundMs >= 0 && nowMs - mLastNotFoundMs >= STALE_MS) {
            mVideoId = videoId;
            mVideoNotFoundMs = -1;
            mAudioNotFoundMs = -1;
        }

        mLastNotFoundMs = nowMs;

        if (isVideo && mVideoNotFoundMs < 0) {
            mVideoNotFoundMs = nowMs;
        } else if (!isVideo && mAudioNotFoundMs < 0) {
            mAudioNotFoundMs = nowMs;
        }

        if (mVideoNotFoundMs < 0 || mAudioNotFoundMs < 0) {
            return -1;
        }

        long goneMs = nowMs - Math.max(mVideoNotFoundMs, mAudioNotFoundMs);

        return goneMs >= GONE_MS ? goneMs : -1;
    }

    /**
     * A segment of the track loaded: its 404s were the live edge's, or its URLs are served again (e.g. new ones, fetched again)
     */
    public void onLoaded(boolean isVideo) {
        if (isVideo) {
            mVideoNotFoundMs = -1;
        } else {
            mAudioNotFoundMs = -1;
        }
    }
}
