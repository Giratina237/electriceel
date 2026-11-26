package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import com.google.android.exoplayer2.C;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveHeadWaitsTest {
    private final AtomicLong mNow = new AtomicLong(10_000);

    @Before
    public void setUp() {
        LiveHeadWaits.resetForTesting(mNow::get);
    }

    @After
    public void tearDown() {
        LiveHeadWaits.resetForTesting(null);
    }

    /**
     * From when the request got no segment, for as long as the next one waits, cut to the start's end, with its track
     */
    @Test
    public void aWaitLastsTillTheNextRequest() {
        mNow.set(11_000);
        LiveHeadWaits.onWait(C.TRACK_TYPE_VIDEO, 1_250);
        mNow.set(12_000);
        LiveHeadWaits.onWait(C.TRACK_TYPE_AUDIO, 1_250);

        List<VideoStart.Stage> stages = LiveHeadWaits.take(10_500, 12_500);

        assertEquals(2, stages.size());
        assertEquals(VideoStarts.STAGE_LIVE_HEAD, stages.get(0).name);
        assertEquals("video, next asked in 1250 ms", stages.get(0).detail);
        assertEquals(1, stages.get(0).depth);
        assertEquals(11_000, stages.get(0).startMs);
        assertEquals(12_250, stages.get(0).endMs);
        assertEquals("audio, next asked in 1250 ms", stages.get(1).detail);
        assertEquals(12_000, stages.get(1).startMs);
        assertEquals(12_500, stages.get(1).endMs);
    }

    /**
     * Told before the start, it's of the stream before
     */
    @Test
    public void oneToldBeforeTheStartIsLeftOut() {
        mNow.set(9_000);
        LiveHeadWaits.onWait(C.TRACK_TYPE_VIDEO, 5_000);

        assertTrue(LiveHeadWaits.take(10_000, 15_000).isEmpty());
    }

    /**
     * Past the rows shown, one row from the first to the end of the last, with how many of each track
     */
    @Test
    public void manyWaitsAreOneRow() {
        for (int i = 0; i < LiveHeadWaits.MAX_ROWS + 2; i++) {
            mNow.set(11_000 + i * 300L);
            LiveHeadWaits.onWait(i % 2 == 0 ? C.TRACK_TYPE_VIDEO : C.TRACK_TYPE_AUDIO, 250);
        }

        List<VideoStart.Stage> stages = LiveHeadWaits.take(10_000, 20_000);

        assertEquals(1, stages.size());
        assertEquals("10 waits, video 5, audio 5", stages.get(0).detail);
        assertEquals(11_000, stages.get(0).startMs);
        assertEquals(11_000 + 9 * 300 + 250, stages.get(0).endMs);
    }
}
