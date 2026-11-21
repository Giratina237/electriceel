package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Map;

/**
 * The client a video was moved to after the one it came from refused it (e.g. TV's SABR answering sabr.no_video_selected, see
 * ErrorFixerController#applySabrProtocolErrorAction): opened again, it's asked of that one first, in this run and the next ones, for
 * a week. That video's only: the others go by the order (89HAAW4J, XWR8QQN7, F0SVV9N8: one video refused by TV in four runs, relearned
 * each time in 4-7 s, while TV played the other videos).
 */
public final class VideoClientPicks {
    private static final String PREFS = "video_client_picks";
    @VisibleForTesting
    static final long KEEP_MS = 7 * 24 * 60 * 60 * 1_000L;
    /**
     * The newest ones are kept
     */
    @VisibleForTesting
    static final int MAX_VIDEOS = 50;
    private static final char SEPARATOR = '|';
    @VisibleForTesting
    static Clock sClock = System::currentTimeMillis;

    interface Clock {
        long now();
    }

    private VideoClientPicks() {
    }

    /**
     * @param client e.g. TV_SAMSUNG, one of the order's (see MediaServiceData#getClientOrder)
     */
    public static synchronized void remember(@Nullable Context context, String videoId, String client) {
        if (context == null || videoId == null || client == null) {
            return;
        }

        SharedPreferences prefs = getPrefs(context);
        SharedPreferences.Editor editor = prefs.edit().putString(videoId, client + SEPARATOR + sClock.now());
        Map<String, ?> all = prefs.getAll();

        // The oldest one out (the one being put isn't counted twice)
        if (all.size() >= MAX_VIDEOS && !all.containsKey(videoId)) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;

            for (Map.Entry<String, ?> entry : all.entrySet()) {
                long time = parseTime(entry.getValue());

                if (time < oldestTime) {
                    oldestTime = time;
                    oldest = entry.getKey();
                }
            }

            if (oldest != null) {
                editor.remove(oldest);
            }
        }

        editor.apply();
    }

    /**
     * The client the video was moved to, null for none or one a week old
     */
    @Nullable
    public static synchronized String get(@Nullable Context context, String videoId) {
        if (context == null || videoId == null) {
            return null;
        }

        SharedPreferences prefs = getPrefs(context);
        String value = prefs.getString(videoId, null);

        if (value == null) {
            return null;
        }

        int separator = value.lastIndexOf(SEPARATOR);

        if (separator <= 0 || sClock.now() - parseTime(value) > KEEP_MS) {
            prefs.edit().remove(videoId).apply();
            return null;
        }

        return value.substring(0, separator);
    }

    private static long parseTime(@Nullable Object value) {
        if (!(value instanceof String)) {
            return 0;
        }

        String text = (String) value;
        int separator = text.lastIndexOf(SEPARATOR);

        try {
            return separator >= 0 ? Long.parseLong(text.substring(separator + 1)) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @VisibleForTesting
    static synchronized void resetForTesting(Context context, @Nullable Clock clock) {
        getPrefs(context).edit().clear().commit();
        sClock = clock != null ? clock : System::currentTimeMillis;
    }
}
