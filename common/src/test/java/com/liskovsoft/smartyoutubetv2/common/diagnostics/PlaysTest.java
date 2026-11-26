package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * How a video's play went on from its start, kept with the start whether or not anything failed: QYZPW0FN's video was paused by the
 * media session six times in 13 minutes, and 6EZTNXBF's five plays ended 10 to 381 s in with nothing recorded
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlaysTest {
    private static final long WALL_OFFSET_MS = 1_795_125_600_000L;
    private static final long START_TS = WALL_OFFSET_MS + 47_000;
    private final AtomicLong mNow = new AtomicLong(50_000);
    private final List<Long> mStarts = new ArrayList<>();
    private final List<JSONObject> mPlays = new ArrayList<>();

    @Before
    public void setUp() {
        Plays.resetForTesting();
        PlayerCommands.resetForTesting();
        usePlaysHooks();
    }

    @After
    public void tearDown() {
        VideoStarts.resetForTesting();
        Plays.resetForTesting();
        PlayerCommands.resetForTesting();
    }

    /**
     * A pause the app didn't ask for is written when it comes, with what asked for it, and how long till it was played on
     */
    @Test
    public void aPauseFromOutsideTheAppIsKeptWithThePlay() throws Exception {
        Plays.onStarted("video", START_TS, true);
        mNow.addAndGet(29_234);
        PlayerCommands.onPlayWhenReady(false, PlayerCommands.SOURCE_MEDIA_SESSION, "android", 29_234);
        Plays.onPlaying(false, 29_234);

        assertEquals(1, mPlays.size());
        assertEquals(Long.valueOf(START_TS), mStarts.get(0));
        JSONObject first = mPlays.get(0);
        assertEquals(29_234, first.getLong("playedMs"));
        assertFalse("it goes on", first.has("end"));
        assertEquals(1, first.getJSONObject("pauseCounts").getInt(PlayerCommands.SOURCE_MEDIA_SESSION));
        JSONObject pause = first.getJSONArray("pauses").getJSONObject(0);
        assertEquals(WALL_OFFSET_MS + 50_000 + 29_234, pause.getLong("ts"));
        assertEquals(29_234, pause.getLong("positionMs"));
        assertEquals(PlayerCommands.SOURCE_MEDIA_SESSION, pause.getString("source"));
        assertEquals("android", pause.getString("sender"));
        assertFalse(pause.has("resumedAfterMs"));

        // The user plays it on, and it comes again a minute later
        mNow.addAndGet(4_639);
        PlayerCommands.onPlayWhenReady(true, PlayerCommands.SOURCE_APP, 29_240);
        Plays.onPlaying(true, 29_240);
        mNow.addAndGet(59_931);
        PlayerCommands.onPlayWhenReady(false, PlayerCommands.SOURCE_MEDIA_SESSION, "android", 89_235);

        assertEquals(2, mPlays.size());
        JSONObject second = mPlays.get(1);
        assertEquals(29_234 + 59_931, second.getLong("playedMs"));
        assertEquals(2, second.getJSONObject("pauseCounts").getInt(PlayerCommands.SOURCE_MEDIA_SESSION));
        JSONArray pauses = second.getJSONArray("pauses");
        assertEquals(2, pauses.length());
        assertEquals(4_639, pauses.getJSONObject(0).getLong("resumedAfterMs"));
        assertEquals(PlayerCommands.SOURCE_APP, pauses.getJSONObject(0).getString("resumedBy"));
        // A fixed time apart: a device's, not a person's
        assertEquals(64_570, pauses.getJSONObject(1).getLong("ts") - pauses.getJSONObject(0).getLong("ts"));
    }

    /**
     * The app's own pause (the user's) is counted, and written when the play ends: with where it ended and how long it played
     */
    @Test
    public void aPauseOfTheAppsIsWrittenWithTheEnd() throws Exception {
        Plays.onStarted("video", START_TS, true);
        mNow.addAndGet(10_000);
        PlayerCommands.onPlayWhenReady(false, PlayerCommands.SOURCE_APP, 10_000);
        Plays.onPlaying(false, 10_000);

        assertTrue(mPlays.isEmpty());

        mNow.addAndGet(5_000);
        Plays.onFinish(-1);

        assertEquals(1, mPlays.size());
        JSONObject play = mPlays.get(0);
        assertEquals(Plays.END_LEFT, play.getString("end"));
        assertEquals(10_000, play.getLong("playedMs"));
        assertEquals(10_000, play.getLong("endPositionMs"));
        assertEquals(1, play.getJSONObject("pauseCounts").getInt(PlayerCommands.SOURCE_APP));
        assertFalse(play.getJSONArray("pauses").getJSONObject(0).has("sender"));
        assertFalse(play.getJSONArray("pauses").getJSONObject(0).has("resumedAfterMs"));
    }

    /**
     * It ends when its video does, when another one is loaded, or when its video starts again: once
     */
    @Test
    public void aPlayEndsOnceWithHow() throws Exception {
        Plays.onStarted("first", START_TS, true);
        // A reload of the video goes on with it
        Plays.onNewVideo("first");
        assertTrue(mPlays.isEmpty());

        Plays.onNewVideo("second");
        assertEquals(Plays.END_REPLACED, mPlays.get(0).getString("end"));
        assertEquals(Long.valueOf(START_TS), mStarts.get(0));
        assertFalse("no pause, nothing of them", mPlays.get(0).has("pauseCounts"));
        assertFalse(mPlays.get(0).has("pauses"));

        Plays.onStarted("second", START_TS + 1, true);
        Plays.onStarted("second", START_TS + 2, true);
        assertEquals(Plays.END_RESTARTED, mPlays.get(1).getString("end"));
        assertEquals(Long.valueOf(START_TS + 1), mStarts.get(1));

        mNow.addAndGet(300_000);
        Plays.onEnded(300_500);
        assertEquals(Plays.END_ENDED, mPlays.get(2).getString("end"));
        assertEquals(300_000, mPlays.get(2).getLong("playedMs"));
        assertEquals(300_500, mPlays.get(2).getLong("endPositionMs"));

        // The player closed after it: it ended at its video's end still, and nothing more of it after
        Plays.onFinish(300_500);
        assertEquals(Plays.END_ENDED, mPlays.get(3).getString("end"));
        assertEquals(300_000, mPlays.get(3).getLong("playedMs"));
        Plays.onViewPaused(300_500);
        assertEquals(4, mPlays.size());
    }

    /**
     * 6EZTNXBF: a play that ended 44.2 s in, as the player's screen went behind another. Written there, the app may be closed: that's
     * how it ended unless it's seen again.
     */
    @Test
    public void aPlayWhoseScreenWentBehindAnotherEndedThereUnlessItsSeenAgain() throws Exception {
        Plays.onStarted("video", START_TS, true);
        mNow.addAndGet(44_213);
        Plays.onViewPaused(123_250);

        assertEquals(1, mPlays.size());
        assertEquals(Plays.END_BACKGROUND, mPlays.get(0).getString("end"));
        assertEquals(44_213, mPlays.get(0).getLong("playedMs"));
        assertEquals(123_250, mPlays.get(0).getLong("endPositionMs"));

        // Cleared away there (the player closed in the background)
        Plays.onPlaying(false, -1);
        Plays.onFinish(-1);
        assertEquals(Plays.END_BACKGROUND, mPlays.get(1).getString("end"));

        // Back in front, it goes on: left when the player is closed
        Plays.onStarted("other", START_TS + 1, true);
        Plays.onViewPaused(5_000);
        Plays.onViewResumed();
        mNow.addAndGet(20_000);
        Plays.onFinish(25_000);

        assertEquals(Plays.END_LEFT, mPlays.get(mPlays.size() - 1).getString("end"));
        assertEquals(25_000, mPlays.get(mPlays.size() - 1).getLong("endPositionMs"));
    }

    /**
     * Ready and paused when its start was recorded: nothing played till it's played
     */
    @Test
    public void aStartThatWasPausedPlaysOnceItsPlayed() throws Exception {
        Plays.onStarted("video", START_TS, false);
        mNow.addAndGet(30_000);
        Plays.onPlaying(true, 0);
        mNow.addAndGet(7_000);
        Plays.onPlaying(false, 7_000);
        mNow.addAndGet(60_000);
        Plays.onFinish(-1);

        assertEquals(7_000, mPlays.get(0).getLong("playedMs"));
        assertEquals(7_000, mPlays.get(0).getLong("endPositionMs"));
    }

    @Test
    public void onlyTheLastPausesAreKeptOneByOne() throws Exception {
        Plays.onStarted("video", START_TS, true);

        for (int i = 0; i < Plays.MAX_PAUSES + 3; i++) {
            PlayerCommands.onPlayWhenReady(false, PlayerCommands.SOURCE_APP, i);
            PlayerCommands.onPlayWhenReady(true, PlayerCommands.SOURCE_APP, i);
        }

        Plays.onFinish(-1);

        JSONObject play = mPlays.get(0);
        assertEquals(Plays.MAX_PAUSES + 3, play.getJSONObject("pauseCounts").getInt(PlayerCommands.SOURCE_APP));
        assertEquals(Plays.MAX_PAUSES, play.getJSONArray("pauses").length());
        assertEquals(3, play.getJSONArray("pauses").getJSONObject(0).getLong("positionMs"));
    }

    /**
     * XF9KYTK1 0zujjiTyeiA: a resume from 0:07 was left at 0:00.711, and nothing said what had sent it back. A play's seeks are kept with
     * it, the last ones one by one; one without any has none.
     */
    @Test
    public void aPlaysSeeksAreKeptWithIt() throws Exception {
        Plays.onStarted("video", START_TS, true);
        mNow.addAndGet(150);
        Plays.onSeek(PlayerCommands.SOURCE_APP, 100);

        for (int i = 0; i < Plays.MAX_SEEKS; i++) {
            Plays.onSeek(PlayerCommands.SOURCE_MEDIA_SESSION, 1_000 + i);
        }

        Plays.onFinish(711);
        Plays.onStarted("other", START_TS + 1, true);
        Plays.onFinish(5_000);

        JSONObject play = mPlays.get(0);
        assertEquals(Plays.MAX_SEEKS + 1, play.getInt("seekCount"));
        JSONArray seeks = play.getJSONArray("seeks");
        assertEquals(Plays.MAX_SEEKS, seeks.length());
        JSONObject first = seeks.getJSONObject(0);
        assertEquals(WALL_OFFSET_MS + 50_000 + 150, first.getLong("ts"));
        assertEquals(1_000, first.getLong("positionMs"));
        assertEquals(PlayerCommands.SOURCE_MEDIA_SESSION, first.getString("source"));
        assertFalse("a test's, not the app's code", first.has("caller"));
        assertFalse(mPlays.get(1).has("seekCount"));
        assertFalse(mPlays.get(1).has("seeks"));
    }

    /**
     * No start played (e.g. a card's preview, which isn't timed): nothing to add to
     */
    @Test
    public void withNoStartNothingIsWritten() {
        PlayerCommands.onPlayWhenReady(false, PlayerCommands.SOURCE_MEDIA_SESSION, "android", 1_000);
        Plays.onPlaying(false, 1_000);
        Plays.onViewPaused(1_000);
        Plays.onEnded(1_000);
        Plays.onFinish(1_000);

        assertTrue(mPlays.isEmpty());
    }

    /**
     * A start that played begins its play: what's written of it is that start's
     */
    @Test
    public void aStartThatPlayedBeginsItsPlay() throws Exception {
        VideoStartFixture fixture = new VideoStartFixture();
        // The fixture reset them
        usePlaysHooks();
        VideoStart start = fixture.slowStart();
        mNow.addAndGet(12_000);

        Plays.onFinish(12_000);

        assertEquals(1, mPlays.size());
        assertEquals(Long.valueOf(start.ts), mStarts.get(0));
        assertEquals(12_000, mPlays.get(0).getLong("playedMs"));
        assertEquals(Plays.END_LEFT, mPlays.get(0).getString("end"));
    }

    private void usePlaysHooks() {
        Plays.sClock = mNow::get;
        Plays.sWallClock = () -> WALL_OFFSET_MS + mNow.get();
        Plays.sWriter = (startTs, play) -> {
            mStarts.add(startTs);
            mPlays.add(play);
        };
    }
}
