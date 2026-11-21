package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.VisibleForTesting;

import java.util.Random;

/**
 * Tunneled playback turned off for the rest of the app's run, its setting left as the user set it: the decoder it plays through froze
 * the picture, or the player stalled with its buffer full (see ErrorFixerController). The players made after it play without (see
 * ExoPlayerController#setTrackSelector). A player is reused from one video to the next, so it's the run's, not a video's.<br/>
 * Turned off in two runs, it's suspended on the device: off in every run after, till the user sets the setting again (Z55WD4E0,
 * 0J159Z0Z: the same freeze 14 minutes apart, an app restart between them, each run paying a frozen start to learn it).
 */
public final class TunnelingFallback {
    private static final String PREFS = "tunneling_fallback";
    private static final String KEY_RUNS = "runs";
    private static final String KEY_LAST_RUN = "last_run";
    private static final String KEY_SUSPENDED = "suspended";
    /**
     * The runs it was turned off in before it's suspended (see isSuspended)
     */
    @VisibleForTesting
    static final int RUNS_TO_SUSPEND = 2;
    /**
     * Of this process: a run that turns it off counts once
     */
    @VisibleForTesting
    static long sRunId = new Random().nextLong();
    private static volatile boolean sIsOff;

    private TunnelingFallback() {
    }

    /**
     * @return whether it was on till now: a restart of the engine plays without it. Once off, a restart wouldn't change anything.
     */
    public static boolean turnOff(Context context) {
        boolean wasOn = !isOff(context);
        sIsOff = true;

        if (wasOn) {
            countRun(context);
        }

        return wasOn;
    }

    public static boolean isOff(Context context) {
        return sIsOff || isSuspended(context);
    }

    /**
     * Off in every run, after it was turned off in RUNS_TO_SUSPEND of them: the setting still says on (see reset)
     */
    public static boolean isSuspended(Context context) {
        return context != null && getPrefs(context).getBoolean(KEY_SUSPENDED, false);
    }

    /**
     * The user set the setting (on or off): it's tried again from scratch, the runs counted anew
     */
    public static void reset(Context context) {
        sIsOff = false;
        getPrefs(context).edit().clear().apply();
    }

    private static synchronized void countRun(Context context) {
        if (context == null) {
            return;
        }

        SharedPreferences prefs = getPrefs(context);

        if (prefs.getLong(KEY_LAST_RUN, 0) == sRunId) {
            return;
        }

        int runs = prefs.getInt(KEY_RUNS, 0) + 1;
        prefs.edit()
                .putInt(KEY_RUNS, runs)
                .putLong(KEY_LAST_RUN, sRunId)
                .putBoolean(KEY_SUSPENDED, runs >= RUNS_TO_SUSPEND)
                .apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Another run, as the app's restart starts one
     */
    @VisibleForTesting
    static void newRunForTesting() {
        sIsOff = false;
        sRunId = new Random().nextLong();
    }
}
