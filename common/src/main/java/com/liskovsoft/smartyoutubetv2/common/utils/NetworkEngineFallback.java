package com.liskovsoft.smartyoutubetv2.common.utils;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;

/**
 * OkHttp for one video, whatever engine is chosen in the settings (see ExoMediaSourceFactory#buildHttpDataSourceFactory): the video's
 * SABR response was read out of step (see ErrorFixerController#applyOkHttpFallback), which 20 of 628 plays did on Cronet and none of 679
 * on OkHttp (2026-11-19's submissions). Not saved, and not for the next video: the saved engine is the user's.
 */
public final class NetworkEngineFallback {
    @Nullable
    private static volatile String sVideoId;

    private NetworkEngineFallback() {
    }

    /**
     * From now on, till another video starts (see #onNewVideo)
     */
    public static void useOkHttpFor(@Nullable String videoId) {
        sVideoId = videoId;
    }

    /**
     * Whether the video that is playing (or being loaded) reads with OkHttp
     */
    public static boolean isOkHttp() {
        return sVideoId != null;
    }

    /**
     * Another video than the one it was asked for is started: the chosen engine again. The same video loaded again keeps it.
     */
    public static void onNewVideo(@Nullable String videoId) {
        String current = sVideoId;

        if (current != null && !Helpers.equals(current, videoId)) {
            sVideoId = null;
        }
    }

    /**
     * The state of a test, or of a run before it
     */
    public static void resetForTesting() {
        sVideoId = null;
    }
}
