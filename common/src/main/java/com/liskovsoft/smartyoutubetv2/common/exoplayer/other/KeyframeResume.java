package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

/**
 * Whether a resume goes back to the keyframe before its position (see ExoPlayerController#setResumePositionMs): only when the decoder
 * would take longer to get to the position than going back costs. Going back flushes the decoder and shows a first frame again:
 * 54-116 ms on a Shield (CX17FXZW), 200-434 ms on an onn 4K Pro (6EZTNXBF). There, with every keyframe 500 ms or more before the
 * position gone back to, a 1080p60 resume whose 58 frames took 320 ms to decode played 282 ms later for it.
 */
public final class KeyframeResume {
    /**
     * The decoder would take at least this much longer to get to the position, for the resume to go back to the keyframe
     */
    static final long MIN_SAVED_MS = 500;
    /**
     * What a frame decoded only to get to the position takes, till the decoder has shown its own pace: up to 1080p (5.5 ms for
     * 1080p60 VP9 on an onn 4K Pro, 6EZTNXBF), and above it (7.9 ms for 4K60 VP9 on a Shield, AWHPECZK; 9-13 ms for 4K24 AV1 on an onn
     * 4K Pro, F8DMFX2M)
     */
    static final float FRAME_MS = 6;
    static final float LARGE_FRAME_MS = 9;
    private static final int LARGE_FRAME_HEIGHT = 1080;
    /**
     * Frames decoded, over this long at least, for the decoder's own pace to be gone by
     */
    static final int MIN_PACED_FRAMES = 5;
    static final long MIN_PACED_MS = 20;
    /**
     * Of a video that doesn't tell its own
     */
    private static final float DEFAULT_FRAME_RATE = 30;

    private KeyframeResume() {
    }

    /**
     * @param gapMs from the keyframe to the resume's position
     * @param frameRate of the video, 0 or less when not known
     * @param height of the video, 0 or less when not known
     * @param decodedFrames the frames decoded so far to get to the position (see DebugInfoMediaCodecVideoRenderer#getSkippedToPosition)
     * @param decodingMs since the first of them was decoded
     */
    public static boolean isWorthIt(long gapMs, float frameRate, int height, int decodedFrames, long decodingMs) {
        return getRemainingMs(gapMs, frameRate, height, decodedFrames, decodingMs) >= MIN_SAVED_MS;
    }

    /**
     * How long the decoder still takes to get to the position, as far as can be told: at its own pace once it has shown one, at the
     * usual one before
     */
    public static long getRemainingMs(long gapMs, float frameRate, int height, int decodedFrames, long decodingMs) {
        float remainingFrames = Math.max(0, gapMs * (frameRate > 0 ? frameRate : DEFAULT_FRAME_RATE) / 1_000 - decodedFrames);
        boolean isPaced = decodedFrames >= MIN_PACED_FRAMES && decodingMs >= MIN_PACED_MS;
        float frameMs = isPaced ? (float) decodingMs / decodedFrames : height > LARGE_FRAME_HEIGHT ? LARGE_FRAME_MS : FRAME_MS;

        return Math.round(remainingFrames * frameMs);
    }
}
