package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * Where a tunneled decoder is at against the position, as a failure's details
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TunneledFramesTest {
    private int mSavedEngine;

    @Before
    public void setUp() {
        // Cronet can't load under Robolectric
        mSavedEngine = PlayerTweaksData.instance(getContext()).getPlayerDataSource();
        PlayerTweaksData.instance(getContext()).setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP);
    }

    @After
    public void tearDown() {
        PlayerTweaksData.instance(getContext()).setPlayerDataSource(mSavedEngine);
    }

    /**
     * H5B64KBE #1, as it was: 6.3 s since the decoder showed a frame, 1.5 s given ahead, sent to 21:25 from a keyframe 5.3 s before.
     * The 4 tunneled starts sent to a position that froze couldn't be told from the 3 that didn't by how far their decoder had to go.
     */
    @Test
    public void howFarTheDecoderHadToGetToThePositionIsADetail() {
        PlayerFailure failure = PlayerFailure.create(getContext(), PlaybackHealth.KIND_VIDEO_FROZEN);

        new TunneledFrames(8_743L, 6_326L, 1_489L, 5_300L).addTo(failure);

        assertEquals(true, failure.getDetail("tunneled"));
        assertEquals(8_743L, failure.getDetail("tunneledShownBehindMs"));
        assertEquals(6_326L, failure.getDetail("tunneledShownAgoMs"));
        assertEquals(1_489L, failure.getDetail("tunneledQueuedAheadMs"));
        assertEquals(5_300L, failure.getDetail("tunneledKeyframeBehindMs"));
    }

    /**
     * Nothing given to the decoder since it was sent there: null, not left out
     */
    @Test
    public void noSampleGivenSinceIsNull() {
        PlayerFailure failure = PlayerFailure.create(getContext(), PlaybackHealth.KIND_START_FROZEN);

        new TunneledFrames(null, null, null, null).addTo(failure);

        assertEquals(JSONObject.NULL, failure.getDetail("tunneledKeyframeBehindMs"));
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
