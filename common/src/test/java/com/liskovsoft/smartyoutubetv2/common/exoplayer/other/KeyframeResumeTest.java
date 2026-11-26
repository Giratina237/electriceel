package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A resume goes back to the keyframe before its position only when the decoder would take longer to get there than going back costs
 */
public class KeyframeResumeTest {
    /**
     * 6EZTNXBF: a 1080p60 resume 967 ms past its keyframe. Its 58 frames took 320 ms to decode, and going back to the keyframe made it
     * play 282 ms later.
     */
    @Test
    public void aKeyframeTheDecoderGetsFromSoonIsntGoneBackTo() {
        assertEquals(342, KeyframeResume.getRemainingMs(967, 60, 1080, 1, 0));
        assertFalse(KeyframeResume.isWorthIt(967, 60, 1080, 1, 0));
    }

    /**
     * AWHPECZK: a 4K60 resume whose 210 frames took 1661 ms to decode unseen
     */
    @Test
    public void aKeyframeFarBeforeThePositionIsGoneBackTo() {
        assertTrue(KeyframeResume.isWorthIt(3_500, 60, 2160, 1, 0));
        assertTrue(KeyframeResume.isWorthIt(2_000, 60, 1080, 1, 0));
    }

    /**
     * Larger frames take longer each: the same gap is worth it sooner
     */
    @Test
    public void aLargerVideosFramesTakeLonger() {
        assertFalse(KeyframeResume.isWorthIt(1_200, 60, 1080, 0, 0));
        assertTrue(KeyframeResume.isWorthIt(1_200, 60, 2160, 0, 0));
    }

    /**
     * Once the decoder has shown its pace, that's what the rest is told by
     */
    @Test
    public void theDecodersOwnPaceIsGoneBy() {
        // 2 ms a frame: the 90 left take 180 ms
        assertEquals(180, KeyframeResume.getRemainingMs(2_000, 60, 1080, 30, 60));
        assertFalse(KeyframeResume.isWorthIt(2_000, 60, 1080, 30, 60));
        // 20 ms a frame: 1.8 s
        assertTrue(KeyframeResume.isWorthIt(2_000, 60, 1080, 30, 600));
        // Too few frames to tell a pace by: the usual one
        assertEquals(696, KeyframeResume.getRemainingMs(2_000, 60, 1080, 4, 400));
    }

    /**
     * The decoder went on while the decision waited for the main thread: with nearly all of it decoded, nothing is saved
     */
    @Test
    public void aDecoderNearlyThereIsntGoneBackFrom() {
        assertFalse(KeyframeResume.isWorthIt(2_000, 60, 1080, 115, 600));
        assertEquals(0, KeyframeResume.getRemainingMs(2_000, 60, 1080, 130, 600));
    }

    /**
     * A video that doesn't tell its frame rate is taken for 30 a second
     */
    @Test
    public void aVideoWithNoFrameRateIsTakenForThirty() {
        assertEquals(360, KeyframeResume.getRemainingMs(2_000, -1, 1080, 0, 0));
        assertTrue(KeyframeResume.isWorthIt(4_000, -1, -1, 0, 0));
    }
}
