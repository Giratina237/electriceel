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
 * The kinds of video (a codec at a size and frame rate, e.g. "av1 1080p50") that dropped frames on the device with a decoder that
 * declares them (see ErrorFixerController#onHealthFailure), and the videos they did so on. Kept across runs: the device's decoders drop
 * them in every run alike (27XPFNZC #1, #2 and Y36K953Z #1, runs apart: AV1 and AVC 1080p50 dropped frames on that Sansui, VP9
 * 1080p50 none in 21 min, VRENB3NE), and each run paid two videos of drops to learn it again. A video's is kept KEEP_MS (e.g. a
 * firmware update may mend the decoder).
 */
public final class FrameDropCodecs {
    private static final String PREFS = "frame_drop_codecs";
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
     * E.g. "av1 1080p50"
     *
     * @param size the shorter side, as 1080 of 1920x1080 (or of 1080x1920)
     */
    public static String kindOf(String codec, int size, float frameRate) {
        return String.format(Locale.US, "%s %dp%d", codec, size, Math.round(frameRate));
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
        return context != null && !read(getPrefs(context).getString(kind, null), sClock.now()).isEmpty();
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

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
