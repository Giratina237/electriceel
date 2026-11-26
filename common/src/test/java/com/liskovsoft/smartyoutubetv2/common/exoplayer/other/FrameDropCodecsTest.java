package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 27XPFNZC and Y36K953Z, runs apart: AV1 and AVC 1080p50 dropped frames on that Sansui, each run paying two videos of drops to learn
 * it again. Kept across runs, by codec, size and frame rate.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FrameDropCodecsTest {
    private static final String AV1_1080P50 = FrameDropCodecs.kindOf("av1", 1080, 50);
    private final long[] mNow = {1_800_000_000_000L};

    @Before
    public void setUp() {
        FrameDropCodecs.sClock = () -> mNow[0];
    }

    @After
    public void tearDown() {
        FrameDropCodecs.sClock = System::currentTimeMillis;
    }

    @Test
    public void aKindIsItsCodecSizeAndFrameRate() {
        assertEquals("av1 1080p50", AV1_1080P50);
        assertEquals("avc 1080p30", FrameDropCodecs.kindOf("avc", 1080, 29.97f));
    }

    @Test
    public void eachVideoCountsOnce() {
        assertEquals(1, FrameDropCodecs.add(getContext(), AV1_1080P50, "1YKvmLD3QW4"));
        assertEquals(1, FrameDropCodecs.add(getContext(), AV1_1080P50, "1YKvmLD3QW4"));
        assertEquals(2, FrameDropCodecs.add(getContext(), AV1_1080P50, "g1T4NT2f_dA"));
        assertTrue(FrameDropCodecs.hasDropped(getContext(), AV1_1080P50));
        assertFalse(FrameDropCodecs.hasDropped(getContext(), FrameDropCodecs.kindOf("vp9", 1080, 50)));
    }

    /**
     * The latest few of a kind are kept
     */
    @Test
    public void aKindKeepsItsLatestVideos() {
        for (int i = 0; i < FrameDropCodecs.MAX_VIDEOS + 3; i++) {
            mNow[0] += 1_000;
            FrameDropCodecs.add(getContext(), AV1_1080P50, "video" + i);
        }

        assertEquals(FrameDropCodecs.MAX_VIDEOS, FrameDropCodecs.add(getContext(), AV1_1080P50, "video" + (FrameDropCodecs.MAX_VIDEOS + 2)));
    }

    /**
     * Long ago, it's tried again (e.g. a firmware update mended the decoder)
     */
    @Test
    public void dropsLongAgoAreForgotten() {
        FrameDropCodecs.add(getContext(), AV1_1080P50, "1YKvmLD3QW4");
        mNow[0] += FrameDropCodecs.KEEP_MS + 1;

        assertFalse(FrameDropCodecs.hasDropped(getContext(), AV1_1080P50));
        assertEquals(1, FrameDropCodecs.add(getContext(), AV1_1080P50, "g1T4NT2f_dA"));
    }

    /**
     * Past MAX_KINDS, the kind whose latest drops are the oldest goes
     */
    @Test
    public void theStalestKindGoesPastTheLimit() {
        for (int i = 0; i < FrameDropCodecs.MAX_KINDS; i++) {
            mNow[0] += 1_000;
            FrameDropCodecs.add(getContext(), FrameDropCodecs.kindOf("av1", 1080 + i, 50), "video");
        }

        mNow[0] += 1_000;
        FrameDropCodecs.add(getContext(), AV1_1080P50 + " new", "video");

        assertFalse(FrameDropCodecs.hasDropped(getContext(), FrameDropCodecs.kindOf("av1", 1080, 50)));
        assertTrue(FrameDropCodecs.hasDropped(getContext(), FrameDropCodecs.kindOf("av1", 1081, 50)));
        assertTrue(FrameDropCodecs.hasDropped(getContext(), AV1_1080P50 + " new"));
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
