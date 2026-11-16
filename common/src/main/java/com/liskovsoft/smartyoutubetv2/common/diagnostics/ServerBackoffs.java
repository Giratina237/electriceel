package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.os.SystemClock;

import androidx.annotation.VisibleForTesting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The waits SABR's server asked for (see SabrStream#setBackoffListener). One under 15 s isn't waited (see SabrExtractorInput), so a
 * segment asked for in it may be answered late, unless it's the answer to an init request that brought no init segment, which is
 * waited (see DefaultSabrChunkSource#maybeWaitForInit): shown in a start's buffering (see VideoStarts). Told on the loader's thread,
 * read on the main one.
 */
public final class ServerBackoffs {
    /**
     * Of a start, rows of their own: more are one row, the hold (see #take)
     */
    static final int MAX_BACKOFFS = 8;
    /**
     * The last ones: a start only needs the ones since it was prepared, all of them, to tell how long the server held it and how often
     * it was asked meanwhile. Only the last 8 were kept, and every start of a WEB stream on a DuneHD showed 8, counting down to the
     * same moment after seconds with no row (KKSJMSQX): any told before them were gone. A start is given up after 15 s.
     */
    private static final int MAX_KEPT = 1_000;
    private static final ArrayDeque<long[]> sBackoffs = new ArrayDeque<>(); // {told, asked}
    @VisibleForTesting
    static VideoStarts.Clock sClock = SystemClock::elapsedRealtime;

    private ServerBackoffs() {
    }

    public static synchronized void onBackoff(int backoffMs) {
        sBackoffs.addLast(new long[]{sClock.now(), backoffMs});

        if (sBackoffs.size() > MAX_KEPT) {
            sBackoffs.removeFirst();
        }
    }

    /**
     * The ones told between the times, from then for as long as asked, cut to the end, as stages of a start's buffering (in the
     * clock's time). One told before is of the stream before. Past MAX_BACKOFFS, one row: the server held the start, from the first
     * told to the end of the last, with how many there were and the first and last waits asked, e.g. "57 waits, first 3990 ms asked,
     * last 7 ms asked".
     */
    static synchronized List<VideoStart.Stage> take(long fromMs, long toMs) {
        List<long[]> told = new ArrayList<>();

        for (long[] backoff : sBackoffs) {
            long startMs = backoff[0];

            if (startMs >= fromMs && getEndMs(backoff, toMs) - startMs >= 1) {
                told.add(backoff);
            }
        }

        List<VideoStart.Stage> result = new ArrayList<>();

        if (told.size() <= MAX_BACKOFFS) {
            for (long[] backoff : told) {
                result.add(new VideoStart.Stage(VideoStarts.STAGE_SERVER_BACKOFF, String.format(Locale.US, "%d ms asked", backoff[1]), 1,
                        backoff[0], getEndMs(backoff, toMs)));
            }

            return result;
        }

        long[] first = told.get(0);
        long[] last = told.get(told.size() - 1);
        long endMs = first[0];

        for (long[] backoff : told) {
            endMs = Math.max(endMs, getEndMs(backoff, toMs));
        }

        // The oldest kept is of this start: more were told before it
        long[] oldest = sBackoffs.peekFirst();
        boolean isCut = sBackoffs.size() >= MAX_KEPT && oldest != null && oldest[0] >= fromMs;
        String detail = String.format(Locale.US, "%d%s waits, first %d ms asked, last %d ms asked", told.size(), isCut ? "+" : "", first[1],
                last[1]);
        result.add(new VideoStart.Stage(VideoStarts.STAGE_SERVER_HOLD, detail, 1, first[0], endMs));

        return result;
    }

    /**
     * When the wait asked for ends, cut to the time
     */
    private static long getEndMs(long[] backoff, long toMs) {
        return Math.min(backoff[0] + backoff[1], toMs);
    }

    @VisibleForTesting
    static synchronized void resetForTesting(VideoStarts.Clock clock) {
        sBackoffs.clear();
        sClock = clock != null ? clock : SystemClock::elapsedRealtime;
    }
}
