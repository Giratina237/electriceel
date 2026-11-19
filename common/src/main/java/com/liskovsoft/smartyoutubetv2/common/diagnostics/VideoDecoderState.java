package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import androidx.annotation.Nullable;

/**
 * The player's video decoder as its health was last sampled (see ExoPlayerController#checkHealth, every 2 s or closer), for the failures
 * recorded where the renderer isn't at hand (e.g. a long stall's, see ErrorFixerController#onLongBuffering) and the fixes that depend on
 * it. Main thread.
 */
public final class VideoDecoderState {
    private static boolean sIsSampled;
    /**
     * -1 when unknown, e.g. a tunneled decoder that doesn't report each frame it shows
     */
    private static long sInputBuffers = -1;
    private static long sRenderedFrames = -1;
    private static boolean sIsTunneling;
    @Nullable
    private static TunneledFrames sTunneledFrames;

    private VideoDecoderState() {
    }

    /**
     * @param tunneledFrames null when it isn't tunneled
     */
    public static void onSample(long inputBuffers, long renderedFrames, boolean isTunneling, @Nullable TunneledFrames tunneledFrames) {
        sIsSampled = true;
        sInputBuffers = inputBuffers;
        sRenderedFrames = renderedFrames;
        sIsTunneling = isTunneling;
        sTunneledFrames = tunneledFrames;
    }

    /**
     * The player was released: what's known is of no decoder in use
     */
    public static void reset() {
        sIsSampled = false;
        sInputBuffers = -1;
        sRenderedFrames = -1;
        sIsTunneling = false;
        sTunneledFrames = null;
    }

    /**
     * The decoder shows the frames itself, in step with the audio (see DebugInfoMediaCodecVideoRenderer#isTunneling)
     */
    public static boolean isTunneling() {
        return sIsTunneling;
    }

    /**
     * As a failure's details: videoInputBuffers and videoRenderedFrames (null when unknown), and a tunneled decoder's (see
     * #addTunnelingDetails). None when nothing was sampled.
     */
    public static void addDetails(PlayerFailure failure) {
        if (!sIsSampled) {
            return;
        }

        failure.detail("videoInputBuffers", sInputBuffers >= 0 ? sInputBuffers : null)
                .detail("videoRenderedFrames", sRenderedFrames >= 0 ? sRenderedFrames : null);
        addTunnelingDetails(failure);
    }

    /**
     * Where a tunneled decoder is at against the position (see TunneledFrames#addTo), none when it isn't tunneled
     */
    public static void addTunnelingDetails(PlayerFailure failure) {
        TunneledFrames frames = sTunneledFrames;

        if (sIsTunneling && frames != null) {
            frames.addTo(failure);
        }
    }
}
