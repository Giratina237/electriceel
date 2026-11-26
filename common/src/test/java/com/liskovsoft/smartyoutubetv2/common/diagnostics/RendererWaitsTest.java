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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Which renderer the player waited for: 6EZTNXBF's first frame was shown at 1226 ms and it played at 2882 ms with 30.8 s buffered,
 * and nothing told whether the sound or the picture held it
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RendererWaitsTest {
    private final AtomicLong mNow = new AtomicLong(1_000);

    @Before
    public void setUp() {
        RendererWaits.resetForTesting(mNow::get);
    }

    @After
    public void tearDown() {
        RendererWaits.resetForTesting(null);
    }

    /**
     * Told each time the playback loop looks: a wait is from the first time it isn't ready to the first time it is again
     */
    @Test
    public void aRendererThatIsntReadyIsAWaitTillItIs() {
        RendererWaits.onReadiness(C.TRACK_TYPE_VIDEO, true);
        mNow.set(1_226);
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, false);
        mNow.set(1_236);
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, false);
        RendererWaits.onReadiness(C.TRACK_TYPE_VIDEO, true);
        mNow.set(2_880);
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, true);
        mNow.set(2_890);
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, true);

        List<VideoStart.Stage> stages = RendererWaits.take(1_000, 3_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.STAGE_AUDIO_NOT_READY, stages.get(0).name);
        assertNull(stages.get(0).detail);
        assertEquals(1, stages.get(0).depth);
        assertEquals(1_226, stages.get(0).startMs);
        assertEquals(2_880, stages.get(0).endMs);
    }

    /**
     * Cut to the times asked for (a start's first frame and its end): what's left of a short one says nothing
     */
    @Test
    public void aWaitIsCutToTheTimesAndAShortOneLeftOut() {
        mNow.set(900);
        RendererWaits.onReadiness(C.TRACK_TYPE_VIDEO, false);
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, false);
        mNow.set(1_300);
        RendererWaits.onReadiness(C.TRACK_TYPE_VIDEO, true);
        mNow.set(1_600);
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, true);

        List<VideoStart.Stage> stages = RendererWaits.take(1_226, 2_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.STAGE_AUDIO_NOT_READY, stages.get(0).name);
        assertEquals(1_226, stages.get(0).startMs);
        assertEquals(1_600, stages.get(0).endMs);
    }

    /**
     * Of a start that never played: what it still waits for
     */
    @Test
    public void oneStillGoingSaysSo() {
        mNow.set(1_100);
        RendererWaits.onReadiness(C.TRACK_TYPE_VIDEO, false);

        List<VideoStart.Stage> stages = RendererWaits.take(1_000, 2_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.STAGE_VIDEO_NOT_READY, stages.get(0).name);
        assertEquals(VideoStarts.DETAIL_GOING, stages.get(0).detail);
        assertEquals(1_100, stages.get(0).startMs);
        assertEquals(2_000, stages.get(0).endMs);
    }

    /**
     * The player prepared with another source: its renderers start over
     */
    @Test
    public void theWaitsOfTheSourceBeforeArentThisOnes() {
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, false);
        mNow.set(2_000);
        RendererWaits.onPrepare();

        assertTrue(RendererWaits.take(0, 5_000).isEmpty());

        // Not ready still: a wait of the new source's, from when it's told
        mNow.set(3_000);
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, false);
        mNow.set(3_500);
        RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, true);

        List<VideoStart.Stage> stages = RendererWaits.take(0, 5_000);

        assertEquals(1, stages.size());
        assertEquals(3_000, stages.get(0).startMs);
        assertEquals(3_500, stages.get(0).endMs);
    }

    /**
     * The subtitles' renderer and the others hold nothing up
     */
    @Test
    public void onlyTheVideosAndTheAudiosAreKept() {
        RendererWaits.onReadiness(C.TRACK_TYPE_TEXT, false);
        RendererWaits.onReadiness(C.TRACK_TYPE_METADATA, false);
        mNow.set(4_000);

        assertTrue(RendererWaits.take(0, 5_000).isEmpty());
    }

    @Test
    public void onlyTheLastOnesAreKept() {
        for (int i = 0; i < RendererWaits.MAX_WAITS + 2; i++) {
            mNow.set(10_000 + i * 1_000L);
            RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, false);
            mNow.set(10_000 + i * 1_000L + 300);
            RendererWaits.onReadiness(C.TRACK_TYPE_AUDIO, true);
        }

        List<VideoStart.Stage> stages = RendererWaits.take(0, 100_000);

        assertEquals(RendererWaits.MAX_WAITS, stages.size());
        assertEquals(12_000, stages.get(0).startMs);
    }
}
