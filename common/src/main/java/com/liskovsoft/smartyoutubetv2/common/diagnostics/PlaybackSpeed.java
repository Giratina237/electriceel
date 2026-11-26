package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import androidx.annotation.VisibleForTesting;

/**
 * The player's speed (its PlaybackParameters), for the records: a failure's (see DiagnosticsReport#buildPlaybackSettings), the time each
 * format attempt and each play played at each speed (see FormatAttempts, Plays), a start's first seconds (see VideoStarts#onFrames). A
 * 50 fps video at 1.5x is 75 frames a second to decode: a Sansui's frame drops of AV1 and AVC 1080p50 were all at 1.5x, its 1x plays of
 * them dropped none, and the speed was found only from the position against the time (0HCC641V, 27XPFNZC, Y36K953Z). Used on the main
 * thread.
 */
public final class PlaybackSpeed {
    private static float sSpeed = 1f;

    private PlaybackSpeed() {
    }

    /**
     * 1 for the normal speed
     */
    public static float get() {
        return sSpeed;
    }

    /**
     * The player that plays is at this speed (it changed, or another player plays, e.g. a card's preview): the time played till now
     * was at the speed before
     */
    public static void onChanged(float speed) {
        float rounded = round(speed);

        if (rounded <= 0 || rounded == sSpeed) {
            return;
        }

        FormatAttempts.onSpeedChanged();
        Plays.onSpeedChanged();
        sSpeed = rounded;
    }

    /**
     * To the hundredth, as the player's menu has them: 1.1f is 1.1, not 1.100000023841858 in the report
     */
    static float round(float speed) {
        return Math.round(speed * 100) / 100f;
    }

    @VisibleForTesting
    static void resetForTesting() {
        sSpeed = 1f;
    }
}
