package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

/**
 * The live position of a live stream: how far behind the end of its window it's played, and when it falls behind it.<br/>
 * One rule for the jump to it (Next, see {@link VideoStateController}) and the player's live indicator, so the indicator
 * reads "behind" exactly when Next would jump.
 */
public final class LiveEdge {
    private static final long OFFICIAL_LIVE_BUFFER_MS = 15_000; // Official app buffer
    private static final long LIVE_BUFFER_MS = OFFICIAL_LIVE_BUFFER_MS;
    private static final long SHORT_LIVE_BUFFER_MS = 0; // Note, on buffer lower than the 60sec you'll notice segment skip
    /**
     * Past the buffer a stream still counts as live: the playback drifts a few seconds behind it (buffering, a short pause)
     */
    private static final long BEHIND_MARGIN_MS = 5_000;

    private LiveEdge() {
    }

    /**
     * How far behind the end of the window a live stream is played
     * @param isBufferDisabled "Disable buffer on streams" (PlayerTweaksData)
     */
    public static long getBufferMs(boolean isBufferDisabled) {
        return isBufferDisabled ? SHORT_LIVE_BUFFER_MS : LIVE_BUFFER_MS;
    }

    /**
     * How far behind the end of the window a live stream counts as behind the live position
     * @param isBufferDisabled "Disable buffer on streams" (PlayerTweaksData)
     */
    public static long getThresholdMs(boolean isBufferDisabled) {
        return getBufferMs(isBufferDisabled) + BEHIND_MARGIN_MS;
    }

    /**
     * The position is behind the live one (only meaningful on a live stream)
     * @param durationMs the end of the stream's window
     * @param isBufferDisabled "Disable buffer on streams" (PlayerTweaksData)
     */
    public static boolean isBehind(long durationMs, long positionMs, boolean isBufferDisabled) {
        return durationMs - positionMs > getThresholdMs(isBufferDisabled);
    }
}
