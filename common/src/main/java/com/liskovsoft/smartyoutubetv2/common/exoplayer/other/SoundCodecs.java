package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

/**
 * The audio codecs (e.g. "audio/opus") whose sound the device played, decoded with sound in it (see SilenceDetector): a stream of one
 * silent from its start is then the video's (e.g. a silent intro), not the device's decoding or output of the codec, and another codec
 * of it isn't tried (see ErrorFixerController#applySilentAudioFallback). Kept across runs, KEEP_MS each (e.g. a firmware update may
 * break a decoder).
 */
public final class SoundCodecs {
    private static final String PREFS = "sound_codecs";
    @VisibleForTesting
    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1_000;
    /**
     * Heard again sooner than this, it isn't written again: each play would write it
     */
    private static final long REWRITE_MS = 24 * 60 * 60 * 1_000;
    @VisibleForTesting
    static Clock sClock = System::currentTimeMillis;

    @VisibleForTesting
    interface Clock {
        long now();
    }

    private SoundCodecs() {
    }

    /**
     * The codec's sound was heard
     *
     * @param mimeType of the format, e.g. "audio/opus"
     */
    public static synchronized void onSound(@Nullable Context context, @Nullable String mimeType) {
        if (context == null || mimeType == null) {
            return;
        }

        SharedPreferences prefs = getPrefs(context);
        long now = sClock.now();
        long heardMs = prefs.getLong(mimeType, -1);

        if (heardMs > 0 && now >= heardMs && now - heardMs < REWRITE_MS) {
            return;
        }

        prefs.edit().putLong(mimeType, now).apply();
    }

    /**
     * How long ago the codec's sound was last heard (see #onSound), -1 for not within KEEP_MS
     */
    public static synchronized long getHeardAgoMs(@Nullable Context context, @Nullable String mimeType) {
        if (context == null || mimeType == null) {
            return -1;
        }

        long heardMs = getPrefs(context).getLong(mimeType, -1);
        long agoMs = sClock.now() - heardMs;

        return heardMs > 0 && agoMs >= 0 && agoMs <= KEEP_MS ? agoMs : -1;
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
