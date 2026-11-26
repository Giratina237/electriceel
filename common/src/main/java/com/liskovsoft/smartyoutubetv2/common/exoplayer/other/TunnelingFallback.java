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
 * 0J159Z0Z: the same freeze 14 minutes apart, an app restart between them, each run paying a frozen start to learn it). Set again
 * after that, one more frozen run suspends it again (see #reset).
 */
public final class TunnelingFallback {
    private static final String PREFS = "tunneling_fallback";
    private static final String KEY_RUNS = "runs";
    private static final String KEY_LAST_RUN = "last_run";
    private static final String KEY_SUSPENDED = "suspended";
    private static final String KEY_RESET_AT = "reset_at";
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
    @VisibleForTesting
    static Clock sClock = System::currentTimeMillis;

    @VisibleForTesting
    interface Clock {
        long now();
    }

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
     * The user set the setting (on or off): it's tried again. Suspended on the device before, one frozen run suspends it again, this one
     * too: its runs are counted from one short of it. H5B64KBE #1 suspended it, the user turned the setting off and on 4 minutes later,
     * and the very next tunneled start froze (Y1D66CNW #1), the device's fourth freeze of the kind, with two more runs to go before it
     * was suspended again. When the user did it is kept (see #getSuspendedResetAt): only the decoder's name told it before.
     */
    public static void reset(Context context) {
        sIsOff = false;
        SharedPreferences prefs = getPrefs(context);
        // Set off and on, the second one finds it no longer suspended: the first one's time stays
        long resetAtMs = prefs.getBoolean(KEY_SUSPENDED, false) ? sClock.now() : prefs.getLong(KEY_RESET_AT, -1);
        SharedPreferences.Editor editor = prefs.edit().clear();

        if (resetAtMs > 0) {
            editor.putInt(KEY_RUNS, RUNS_TO_SUSPEND - 1)
                    .putLong(KEY_RESET_AT, resetAtMs);
        }

        editor.apply();
    }

    /**
     * When the user last set the setting while it was suspended (see #reset), by the device's clock: -1 for never
     */
    public static long getSuspendedResetAt(Context context) {
        return context != null ? getPrefs(context).getLong(KEY_RESET_AT, -1) : -1;
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

    /**
     * As on a new install: never turned off, nor suspended and set again
     */
    @VisibleForTesting
    static void clearForTesting(Context context) {
        sIsOff = false;
        getPrefs(context).edit().clear().apply();
    }
}
