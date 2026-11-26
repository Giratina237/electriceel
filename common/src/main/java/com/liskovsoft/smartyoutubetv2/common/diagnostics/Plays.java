package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a video's play went on from its start (see VideoStarts): how long it played, each time it was paused and what asked for it, and
 * how it ended. Kept with its start (see VideoStartLog#addPlay), failure or not. A video "stopping at random" was a pause from the
 * media session about every 64.5 s, each played on by the user (QYZPW0FN: six in 13 minutes, known only because a failure that
 * followed had the player's last commands); with no failure, five plays that ended 10 to 381 s in recorded nothing of it (6EZTNXBF).
 * Used on the main thread.
 */
public final class Plays {
    /**
     * The video reached its end, and wasn't played again since (see #onEnded)
     */
    public static final String END_ENDED = "ended";
    /**
     * The player was closed
     */
    public static final String END_LEFT = "left";
    /**
     * Another video was loaded in the player
     */
    public static final String END_REPLACED = "replaced";
    /**
     * The video started again (a reload timed as a start of its own, or opened again): what went on is with that start
     */
    public static final String END_RESTARTED = "restarted";
    /**
     * The player's screen went behind another (the app sent to the background, a dialog over it, the screen it was opened from when
     * the player is kept), and it wasn't seen playing since: e.g. the app was closed there
     */
    public static final String END_BACKGROUND = "background";
    /**
     * The last ones are kept one by one: how many there were is in the counts
     */
    static final int MAX_PAUSES = 20;
    /**
     * The last ones are kept one by one: how many there were is in the count
     */
    static final int MAX_SEEKS = 10;
    @VisibleForTesting
    static VideoStarts.Clock sClock = SystemClock::elapsedRealtime;
    @VisibleForTesting
    static VideoStarts.Clock sWallClock = System::currentTimeMillis;
    @VisibleForTesting
    static Writer sWriter = Plays::save;
    /**
     * The application's (no activity kept), for the log of the starts
     */
    @SuppressLint("StaticFieldLeak")
    private static Context sContext;
    /**
     * The one going on, null for none: no start played, or its play ended
     */
    @Nullable
    private static Play sPlay;

    interface Writer {
        /**
         * @param startTs of the start it's the play of (see VideoStart#ts)
         */
        void write(long startTs, JSONObject play);
    }

    private static final class Pause {
        /**
         * Wall clock
         */
        final long ts;
        /**
         * In the clock's time
         */
        final long timeMs;
        final long positionMs;
        final String source;
        @Nullable
        final String sender;
        /**
         * -1 while it's paused
         */
        long resumedAfterMs = -1;
        @Nullable
        String resumedBy;

        Pause(long ts, long timeMs, long positionMs, String source, @Nullable String sender) {
            this.ts = ts;
            this.timeMs = timeMs;
            this.positionMs = positionMs;
            this.source = source;
            this.sender = sender;
        }
    }

    private static final class Seek {
        /**
         * Wall clock
         */
        final long ts;
        /**
         * Where to
         */
        final long positionMs;
        final String source;
        /**
         * The app's code that sent it (see VideoStarts#describeSeekCaller), null when not known
         */
        @Nullable
        final String caller;

        Seek(long ts, long positionMs, String source, @Nullable String caller) {
            this.ts = ts;
            this.positionMs = positionMs;
            this.source = source;
            this.caller = caller;
        }
    }

    private static final class Play {
        final String videoId;
        final long startTs;
        long playedMs;
        /**
         * In the clock's time, -1 when it doesn't play
         */
        long playingSinceMs = -1;
        /**
         * By what asked for them (see PlayerCommands#SOURCE_APP), in the order each first did
         */
        final Map<String, Integer> pauseCounts = new LinkedHashMap<>();
        final ArrayDeque<Pause> pauses = new ArrayDeque<>();
        /**
         * The last pause, till it's played on
         */
        @Nullable
        Pause openPause;
        int seekCount;
        final ArrayDeque<Seek> seeks = new ArrayDeque<>();
        /**
         * How it ended, or was last seen (see END_BACKGROUND), null while it goes on
         */
        @Nullable
        String end;
        /**
         * Where it was last known to be, -1 for not known
         */
        long positionMs = -1;

        Play(String videoId, long startTs) {
            this.videoId = videoId;
            this.startTs = startTs;
        }
    }

    private Plays() {
    }

    /**
     * For the log of the starts
     */
    static void init(Context context) {
        if (context != null) {
            sContext = context.getApplicationContext();
        }
    }

    /**
     * A start got ready (see VideoStarts#finish): its play begins. One going on ends there.
     *
     * @param startTs the start's time (see VideoStart#ts): what its play is kept by
     * @param isPlaying it plays, not ready and paused (see VideoStart#OUTCOME_PAUSED)
     */
    static void onStarted(String videoId, long startTs, boolean isPlaying) {
        Play before = sPlay;

        if (before != null) {
            end(before.videoId.equals(videoId) ? END_RESTARTED : END_REPLACED, -1);
        }

        Play play = new Play(videoId, startTs);
        play.playingSinceMs = isPlaying ? sClock.now() : -1;
        sPlay = play;
    }

    /**
     * Playing, or not anymore (paused, buffering, the engine released)
     *
     * @param positionMs where it is, -1 when not known
     */
    public static void onPlaying(boolean isPlaying, long positionMs) {
        Play play = sPlay;

        if (play == null) {
            return;
        }

        setPosition(play, positionMs);

        if (isPlaying) {
            if (play.playingSinceMs < 0) {
                play.playingSinceMs = sClock.now();
            }

            // Seen playing: it didn't end at its video's end (played again), nor where its screen went behind another
            play.end = null;
        } else {
            stop(play);
        }
    }

    /**
     * The player was told to play or pause (see PlayerCommands#onPlayWhenReady). A pause the app didn't ask for (its UI, the remote's
     * keys it handles) is written at once: the app may be closed before the play ends.
     */
    static void onCommand(boolean playWhenReady, String source, @Nullable String sender, long positionMs) {
        Play play = sPlay;

        if (play == null) {
            return;
        }

        long nowMs = sClock.now();
        setPosition(play, positionMs);

        if (playWhenReady) {
            Pause paused = play.openPause;

            if (paused != null) {
                paused.resumedAfterMs = nowMs - paused.timeMs;
                paused.resumedBy = source;
                play.openPause = null;
            }

            return;
        }

        Pause pause = new Pause(sWallClock.now(), nowMs, positionMs, source, sender);
        play.pauses.addLast(pause);

        if (play.pauses.size() > MAX_PAUSES) {
            play.pauses.removeFirst();
        }

        Integer count = play.pauseCounts.get(source);
        play.pauseCounts.put(source, count != null ? count + 1 : 1);
        play.openPause = pause;

        if (!PlayerCommands.SOURCE_APP.equals(source)) {
            write(play);
        }
    }

    /**
     * The player was told to go to a position (see PlayerCommands#onSeek): kept with the play, written with it. A resume from 0:07 was
     * left at 0:00.711 after 858 ms played, the position the player itself gave, and nothing said what had sent it back (XF9KYTK1
     * 0zujjiTyeiA): a start's seeks are recorded only till its first frame.
     *
     * @param positionMs where to
     */
    static void onSeek(String source, long positionMs) {
        Play play = sPlay;

        if (play == null) {
            return;
        }

        play.seekCount++;
        play.seeks.addLast(new Seek(sWallClock.now(), positionMs, source,
                VideoStarts.describeSeekCaller(Thread.currentThread().getStackTrace())));

        if (play.seeks.size() > MAX_SEEKS) {
            play.seeks.removeFirst();
        }
    }

    /**
     * Another video is loaded in the player: the play of the one before ended. The same one again (a reload) goes on.
     */
    public static void onNewVideo(@Nullable String videoId) {
        Play play = sPlay;

        if (play != null && !play.videoId.equals(videoId)) {
            end(END_REPLACED, -1);
        }
    }

    /**
     * The video reached its end: written as it is. Played again from there (repeated, see VideoLoaderController#applyPlaybackModeInt,
     * or gone back in), it goes on: a repeat is a seek to its start, no start of its own, and the pauses of its next passes had no
     * play to be kept with.
     */
    public static void onEnded(long positionMs) {
        Play play = sPlay;

        if (play == null) {
            return;
        }

        stop(play);
        setPosition(play, positionMs);
        play.end = END_ENDED;
        write(play);
    }

    /**
     * The player was closed
     */
    public static void onFinish(long positionMs) {
        end(END_LEFT, positionMs);
    }

    /**
     * The player's screen went behind another: written as it is, the app may be closed there. It goes on when it's seen again.
     */
    public static void onViewPaused(long positionMs) {
        Play play = sPlay;

        if (play == null) {
            return;
        }

        setPosition(play, positionMs);

        // One that reached its end ended there
        if (play.end == null) {
            play.end = END_BACKGROUND;
        }

        write(play);
    }

    /**
     * The player's screen is back in front: its play goes on. Written, what's kept said it ended behind another screen: closed there
     * later with nothing more written (e.g. a crash), it would have ended where a dialog was opened over it.
     */
    public static void onViewResumed() {
        Play play = sPlay;

        if (play != null && END_BACKGROUND.equals(play.end)) {
            play.end = null;
            write(play);
        }
    }

    /**
     * @param how one of the END_ constants. One that reached its end, or whose screen went behind another, and wasn't seen playing
     *            since ended there, however it's cleared away later (e.g. the next video loaded, the player closed in the background).
     */
    private static void end(String how, long positionMs) {
        Play play = sPlay;

        if (play == null) {
            return;
        }

        sPlay = null;
        stop(play);
        setPosition(play, positionMs);

        if (play.end == null) {
            play.end = how;
        }

        write(play);
    }

    private static void stop(Play play) {
        if (play.playingSinceMs >= 0) {
            play.playedMs += Math.max(0, sClock.now() - play.playingSinceMs);
            play.playingSinceMs = -1;
        }
    }

    private static void setPosition(Play play, long positionMs) {
        if (positionMs >= 0) {
            play.positionMs = positionMs;
        }
    }

    private static void write(Play play) {
        try {
            sWriter.write(play.startTs, toJson(play));
        } catch (RuntimeException e) {
            // The start is kept without it
            android.util.Log.e(VideoStarts.LOG_TAG, "Can't write the play: " + e.getMessage());
        }
    }

    /**
     * {"playedMs", "end", "endPositionMs", "pauseCounts": {source: count}, "pauses": [{"ts", "positionMs", "source", "sender",
     * "resumedAfterMs", "resumedBy"}], "seekCount", "seeks": [{"ts", "positionMs", "source", "caller"}]}: as it is now, its time played
     * up to now
     */
    private static JSONObject toJson(Play play) {
        JSONObject result = new JSONObject();

        try {
            result.put("playedMs", play.playedMs + (play.playingSinceMs >= 0 ? Math.max(0, sClock.now() - play.playingSinceMs) : 0));

            if (play.end != null) {
                result.put("end", play.end);
            }

            if (play.positionMs >= 0) {
                result.put("endPositionMs", play.positionMs);
            }

            if (!play.pauseCounts.isEmpty()) {
                JSONObject counts = new JSONObject();

                for (Map.Entry<String, Integer> count : play.pauseCounts.entrySet()) {
                    counts.put(count.getKey(), count.getValue().intValue());
                }

                result.put("pauseCounts", counts);
                JSONArray pauses = new JSONArray();

                for (Pause pause : play.pauses) {
                    JSONObject item = new JSONObject();
                    item.put("ts", pause.ts);

                    if (pause.positionMs >= 0) {
                        item.put("positionMs", pause.positionMs);
                    }

                    item.put("source", pause.source);

                    if (pause.sender != null) {
                        item.put("sender", pause.sender);
                    }

                    if (pause.resumedAfterMs >= 0) {
                        item.put("resumedAfterMs", pause.resumedAfterMs);
                        item.put("resumedBy", pause.resumedBy);
                    }

                    pauses.put(item);
                }

                result.put("pauses", pauses);
            }

            if (play.seekCount > 0) {
                result.put("seekCount", play.seekCount);
                JSONArray seeks = new JSONArray();

                for (Seek seek : play.seeks) {
                    JSONObject item = new JSONObject();
                    item.put("ts", seek.ts);
                    item.put("positionMs", seek.positionMs);
                    item.put("source", seek.source);

                    if (seek.caller != null) {
                        item.put("caller", seek.caller);
                    }

                    seeks.put(item);
                }

                result.put("seeks", seeks);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }

        return result;
    }

    private static void save(long startTs, JSONObject play) {
        if (sContext != null) {
            VideoStartLog.instance(sContext).addPlay(startTs, play);
        }
    }

    @VisibleForTesting
    static void resetForTesting() {
        sPlay = null;
        sClock = SystemClock::elapsedRealtime;
        sWallClock = System::currentTimeMillis;
        sWriter = Plays::save;
    }
}
