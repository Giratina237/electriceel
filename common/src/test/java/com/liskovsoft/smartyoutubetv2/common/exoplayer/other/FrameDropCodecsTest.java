package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.content.SharedPreferences;

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
 * 27XPFNZC and Y36K953Z, runs apart: AV1 and AVC 1080p50 dropped frames on that Sansui at 1.5x, each run paying two videos of drops to
 * learn it again. Kept across runs, by codec, size and the rate it's decoded at (the video's times the speed: none dropped at 1x).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FrameDropCodecsTest {
    private static final String AV1_1080P50 = FrameDropCodecs.kindOf("av1", 1080, 50, 1);
    private final long[] mNow = {1_800_000_000_000L};

    @Before
    public void setUp() {
        FrameDropCodecs.sClock = () -> mNow[0];
        FrameDropCodecs.resetForTesting();
    }

    @After
    public void tearDown() {
        FrameDropCodecs.sClock = System::currentTimeMillis;
    }

    @Test
    public void aKindIsItsCodecSizeAndFrameRate() {
        assertEquals("av1 1080p50", AV1_1080P50);
        assertEquals("avc 1080p30", FrameDropCodecs.kindOf("avc", 1080, 29.97f, 1));
    }

    /**
     * 0HCC641V: a 1080p50 video at 1.5x is 75 frames a second to decode, its drops aren't the 1x video's (27XPFNZC: none at 1x)
     */
    @Test
    public void aKindIsTheRateItsDecodedAt() {
        assertEquals("av1 1080p75", FrameDropCodecs.kindOf("av1", 1080, 50, 1.5f));
        assertEquals("vp9 1080p63", FrameDropCodecs.kindOf("vp9", 1080, 50, 1.25f));
        // Not known: the normal speed
        assertEquals("av1 1080p50", FrameDropCodecs.kindOf("av1", 1080, 50, 0));
    }

    @Test
    public void theVideosOfAKindAreCounted() {
        assertEquals(0, FrameDropCodecs.count(getContext(), AV1_1080P50));
        FrameDropCodecs.add(getContext(), AV1_1080P50, "1YKvmLD3QW4");
        FrameDropCodecs.add(getContext(), AV1_1080P50, "g1T4NT2f_dA");

        assertEquals(2, FrameDropCodecs.count(getContext(), AV1_1080P50));
        assertEquals(0, FrameDropCodecs.count(null, AV1_1080P50));
    }

    /**
     * What the apps before kept by the video's own frame rate wasn't known to be of 1x: it's cleared, not read
     */
    @Test
    public void theKindsKeptByTheVideosFrameRateAreCleared() {
        SharedPreferences old = getContext().getSharedPreferences("frame_drop_codecs", Context.MODE_PRIVATE);
        old.edit().putString(AV1_1080P50, "1YKvmLD3QW4:" + mNow[0] + ",g1T4NT2f_dA:" + mNow[0]).commit();

        assertFalse(FrameDropCodecs.hasDropped(getContext(), AV1_1080P50));
        assertTrue(old.getAll().isEmpty());
    }

    @Test
    public void eachVideoCountsOnce() {
        assertEquals(1, FrameDropCodecs.add(getContext(), AV1_1080P50, "1YKvmLD3QW4"));
        assertEquals(1, FrameDropCodecs.add(getContext(), AV1_1080P50, "1YKvmLD3QW4"));
        assertEquals(2, FrameDropCodecs.add(getContext(), AV1_1080P50, "g1T4NT2f_dA"));
        assertTrue(FrameDropCodecs.hasDropped(getContext(), AV1_1080P50));
        assertFalse(FrameDropCodecs.hasDropped(getContext(), FrameDropCodecs.kindOf("vp9", 1080, 50, 1)));
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
            FrameDropCodecs.add(getContext(), FrameDropCodecs.kindOf("av1", 1080 + i, 50, 1), "video");
        }

        mNow[0] += 1_000;
        FrameDropCodecs.add(getContext(), AV1_1080P50 + " new", "video");

        assertFalse(FrameDropCodecs.hasDropped(getContext(), FrameDropCodecs.kindOf("av1", 1080, 50, 1)));
        assertTrue(FrameDropCodecs.hasDropped(getContext(), FrameDropCodecs.kindOf("av1", 1081, 50, 1)));
        assertTrue(FrameDropCodecs.hasDropped(getContext(), AV1_1080P50 + " new"));
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
