package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The kinds of video (a codec at a size and the frame rate it's decoded at, e.g. "av1 1080p75") that dropped frames on the device with a
 * decoder that declares the video's frame rate (see ErrorFixerController#onHealthFailure), and the videos they did so on. Kept across
 * runs: the device's decoders drop them in every run alike, and each run paid two videos of drops to learn it again. A video's is kept
 * KEEP_MS (e.g. a firmware update may mend the decoder).
 * <p>
 * The rate is the video's times the playback speed: a Sansui's AV1 and AVC 1080p50 dropped frames on video after video at 1.5x, 75 frames
 * a second (6-14 % of AV1's first 10 s), and none at 1x (27XPFNZC: 0 and 1 of 523 and 532; AV1 1080p60 0 and 1), while VP9 1080p50 kept
 * up at 1.5x (9 of 808, 0HCC641V). Kept by the video's own rate, 1.5x drops took AV1 and AVC 1080p50 off at 1x too (0HCC641V,
 * 27XPFNZC, Y36K953Z: the speed was found only from the position against the time).
 */
public final class FrameDropCodecs {
    private static final String PREFS = "frame_drop_rates";
    /**
     * Of the apps that kept the kinds by the video's own frame rate, whatever the speed (see above): what they kept isn't known to be of
     * 1x, it's cleared once (see #getPrefs)
     */
    private static final String OLD_PREFS = "frame_drop_codecs";
    private static boolean sIsOldCleared;
    @VisibleForTesting
    static final long KEEP_MS = 30L * 24 * 60 * 60 * 1_000;
    /**
     * Of a kind, the latest videos kept: a few more than it takes to tell it (see ErrorFixerController#FRAME_DROP_VIDEOS)
     */
    @VisibleForTesting
    static final int MAX_VIDEOS = 4;
    /**
     * The kinds kept, the one that dropped frames longest ago going first
     */
    @VisibleForTesting
    static final int MAX_KINDS = 16;
    @VisibleForTesting
    static Clock sClock = System::currentTimeMillis;

    @VisibleForTesting
    interface Clock {
        long now();
    }

    private FrameDropCodecs() {
    }

    /**
     * E.g. "av1 1080p75" for a 1080p50 video at 1.5x
     *
     * @param size the shorter side, as 1080 of 1920x1080 (or of 1080x1920)
     * @param frameRate the video's
     * @param speed the playback's, 1 for the normal speed: the decoder decodes frameRate times it a second
     */
    public static String kindOf(String codec, int size, float frameRate, float speed) {
        return String.format(Locale.US, "%s %dp%d", codec, size, Math.round(frameRate * (speed > 0 ? speed : 1)));
    }

    /**
     * The video dropped frames on the kind
     *
     * @return how many videos have, this one included. 0 with no context (e.g. the player's controllers torn down).
     */
    public static synchronized int add(@Nullable Context context, String kind, String videoId) {
        if (context == null) {
            return 0;
        }

        SharedPreferences prefs = getPrefs(context);
        long now = sClock.now();
        Map<String, Long> videos = read(prefs.getString(kind, null), now);
        boolean isNewKind = !prefs.contains(kind);
        // The latest last
        videos.remove(videoId);
        videos.put(videoId, now);

        Iterator<String> oldest = videos.keySet().iterator();

        while (videos.size() > MAX_VIDEOS) {
            oldest.next();
            oldest.remove();
        }

        SharedPreferences.Editor editor = prefs.edit().putString(kind, write(videos));

        if (isNewKind && prefs.getAll().size() >= MAX_KINDS) {
            String stalest = findStalest(prefs, now);

            if (stalest != null) {
                editor.remove(stalest);
            }
        }

        editor.apply();

        return videos.size();
    }

    /**
     * Some video dropped frames on the kind, within KEEP_MS
     */
    public static synchronized boolean hasDropped(@Nullable Context context, String kind) {
        return count(context, kind) > 0;
    }

    /**
     * How many videos dropped frames on the kind, within KEEP_MS (up to MAX_VIDEOS)
     */
    public static synchronized int count(@Nullable Context context, String kind) {
        return context != null ? read(getPrefs(context).getString(kind, null), sClock.now()).size() : 0;
    }

    /**
     * The kind whose latest drops are the oldest
     */
    @Nullable
    private static String findStalest(SharedPreferences prefs, long now) {
        String stalest = null;
        long stalestMs = Long.MAX_VALUE;

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            long latestMs = Long.MIN_VALUE;

            for (Long ms : read(entry.getValue() instanceof String ? (String) entry.getValue() : null, now).values()) {
                latestMs = Math.max(latestMs, ms);
            }

            if (latestMs < stalestMs) {
                stalest = entry.getKey();
                stalestMs = latestMs;
            }
        }

        return stalest;
    }

    /**
     * "videoId:ms,videoId:ms", oldest first: a video ID has no ':' nor ','. Those older than KEEP_MS left out.
     */
    private static Map<String, Long> read(@Nullable String value, long now) {
        Map<String, Long> videos = new LinkedHashMap<>();

        if (value == null || value.isEmpty()) {
            return videos;
        }

        for (String item : value.split(",")) {
            int colon = item.lastIndexOf(':');

            if (colon <= 0) {
                continue;
            }

            try {
                long ms = Long.parseLong(item.substring(colon + 1));

                if (now - ms <= KEEP_MS) {
                    videos.put(item.substring(0, colon), ms);
                }
            } catch (NumberFormatException e) {
                // Not one of ours
            }
        }

        return videos;
    }

    private static String write(Map<String, Long> videos) {
        StringBuilder value = new StringBuilder();

        for (Map.Entry<String, Long> video : videos.entrySet()) {
            if (value.length() > 0) {
                value.append(',');
            }

            value.append(video.getKey()).append(':').append(video.getValue());
        }

        return value.toString();
    }

    @VisibleForTesting
    static synchronized void resetForTesting() {
        sIsOldCleared = false;
    }

    private static SharedPreferences getPrefs(Context context) {
        Context app = context.getApplicationContext();

        if (!sIsOldCleared) {
            sIsOldCleared = true;
            SharedPreferences old = app.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE);

            if (!old.getAll().isEmpty()) {
                old.edit().clear().apply();
            }
        }

        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
