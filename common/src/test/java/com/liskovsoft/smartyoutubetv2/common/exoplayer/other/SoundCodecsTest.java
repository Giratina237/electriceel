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

/**
 * CQHM6BGP #1: a 2-minute silent intro read as Opus that's silent on the device, where Opus had played 74 times. The codecs heard on the
 * device are kept across runs.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SoundCodecsTest {
    private static final String OPUS = "audio/opus";
    private final long[] mNow = {1_800_000_000_000L};

    @Before
    public void setUp() {
        SoundCodecs.sClock = () -> mNow[0];
    }

    @After
    public void tearDown() {
        SoundCodecs.sClock = System::currentTimeMillis;
    }

    @Test
    public void aCodecHeardIsKnownByItsType() {
        assertEquals(-1, SoundCodecs.getHeardAgoMs(getContext(), OPUS));

        SoundCodecs.onSound(getContext(), OPUS);
        mNow[0] += 5_000;

        assertEquals(5_000, SoundCodecs.getHeardAgoMs(getContext(), OPUS));
        assertEquals(-1, SoundCodecs.getHeardAgoMs(getContext(), "audio/mp4a-latm"));
    }

    /**
     * Heard again the same day, it isn't written again: the first time stays
     */
    @Test
    public void heardAgainSoonItStaysAsItWas() {
        SoundCodecs.onSound(getContext(), OPUS);
        mNow[0] += 60_000;
        SoundCodecs.onSound(getContext(), OPUS);

        assertEquals(60_000, SoundCodecs.getHeardAgoMs(getContext(), OPUS));
    }

    /**
     * Not heard for longer than it's kept: not known (e.g. a firmware update since)
     */
    @Test
    public void aCodecHeardLongAgoIsntKnown() {
        SoundCodecs.onSound(getContext(), OPUS);
        mNow[0] += SoundCodecs.KEEP_MS + 1;

        assertEquals(-1, SoundCodecs.getHeardAgoMs(getContext(), OPUS));
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
