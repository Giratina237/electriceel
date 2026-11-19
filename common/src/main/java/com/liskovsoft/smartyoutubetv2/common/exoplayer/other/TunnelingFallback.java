package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

/**
 * Tunneled playback turned off for the rest of the app's run, its setting left as the user set it: the decoder it plays through froze
 * the picture, or the player stalled with its buffer full (see ErrorFixerController). The players made after it play without (see
 * ExoPlayerController#setTrackSelector). A player is reused from one video to the next, so it's the run's, not a video's.
 */
public final class TunnelingFallback {
    private static volatile boolean sIsOff;

    private TunnelingFallback() {
    }

    /**
     * @return whether it was on till now: a restart of the engine plays without it. Once off, a restart wouldn't change anything.
     */
    public static boolean turnOff() {
        boolean wasOn = !sIsOff;
        sIsOff = true;

        return wasOn;
    }

    public static boolean isOff() {
        return sIsOff;
    }
}
