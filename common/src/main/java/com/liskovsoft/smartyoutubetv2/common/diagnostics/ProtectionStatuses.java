package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.source.sabr.parser.parts.PoTokenStatusSabrPart.PoTokenStatus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What SABR's server made of the poToken of each video it streamed (see SabrStream#setProtectionStatusListener): OK, it took it;
 * PENDING, not yet (it sends media for a grace period, about a minute); INVALID, it rejected it; MISSING and PENDING_MISSING, it wants
 * one and none was sent; NOT_REQUIRED, none was sent or needed. Shown in a start's buffering (see VideoStarts) and with a failure of
 * the video (see PlayerFailure), and a stall of a pending one switches the client (see ErrorFixerController). Told on the loaders'
 * threads, read on others.
 */
public final class ProtectionStatuses {
    static final int MAX_VIDEOS = 20;
    /**
     * Of a video: each stream's first one and its changes (a video has a stream per track, and one more each reload)
     */
    static final int MAX_STATUSES = 16;
    /**
     * Of a video, with a failure: the last changes
     */
    static final int MAX_FAILURE_STATUSES = 4;
    private static final Map<String, ArrayDeque<Told>> sStatuses = new LinkedHashMap<String, ArrayDeque<Told>>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Told>> eldest) {
            return size() > MAX_VIDEOS;
        }
    };
    @VisibleForTesting
    static VideoStarts.Clock sClock = SystemClock::elapsedRealtime;

    private static final class Told {
        final long ms;
        final String status;

        Told(long ms, String status) {
            this.ms = ms;
            this.status = status;
        }
    }

    private ProtectionStatuses() {
    }

    public static synchronized void onStatus(@Nullable String videoId, @Nullable PoTokenStatus status) {
        if (videoId == null || status == null) {
            return;
        }

        ArrayDeque<Told> statuses = sStatuses.get(videoId);

        if (statuses == null) {
            statuses = new ArrayDeque<>();
            sStatuses.put(videoId, statuses);
        }

        statuses.addLast(new Told(sClock.now(), status.name()));

        if (statuses.size() > MAX_STATUSES) {
            statuses.removeFirst();
        }
    }

    /**
     * The video's last told, at or after the time (in the clock's time), is that the poToken's attestation is pending: past its
     * grace period, the server sends no more media (see SabrStream#onResponseEnd). One told before is of the stream before. For the
     * error fixer.
     */
    public static synchronized boolean isAttestationPending(@Nullable String videoId, long sinceMs) {
        ArrayDeque<Told> statuses = videoId != null ? sStatuses.get(videoId) : null;
        Told last = statuses != null ? statuses.peekLast() : null;

        return last != null && last.ms >= sinceMs
                && (PoTokenStatus.PENDING.name().equals(last.status) || PoTokenStatus.PENDING_MISSING.name().equals(last.status));
    }

    /**
     * How long the video's attestation has been pending (see isAttestationPending): since the first of its last told in a row that
     * were. -1 when it isn't.
     */
    public static synchronized long getPendingMs(@Nullable String videoId, long sinceMs) {
        if (!isAttestationPending(videoId, sinceMs)) {
            return -1;
        }

        long pendingSinceMs = -1;

        for (Iterator<Told> it = sStatuses.get(videoId).descendingIterator(); it.hasNext(); ) {
            Told told = it.next();

            if (told.ms < sinceMs || !isPending(told.status)) {
                break;
            }

            pendingSinceMs = told.ms;
        }

        return sClock.now() - pendingSinceMs;
    }

    private static boolean isPending(String status) {
        return PoTokenStatus.PENDING.name().equals(status) || PoTokenStatus.PENDING_MISSING.name().equals(status);
    }

    /**
     * The video's told between the times (in the clock's time), as moments of a start's buffering: the first, then each change. One
     * told before is of the stream before.
     */
    static synchronized List<VideoStart.Stage> take(String videoId, long fromMs, long toMs) {
        List<VideoStart.Stage> result = new ArrayList<>();
        ArrayDeque<Told> statuses = videoId != null ? sStatuses.get(videoId) : null;

        if (statuses == null) {
            return result;
        }

        String last = null;

        for (Told told : statuses) {
            if (told.ms < fromMs || told.ms > toMs || told.status.equals(last)) {
                continue;
            }

            last = told.status;
            result.add(new VideoStart.Stage(VideoStarts.STAGE_PROTECTION, told.status, 1, told.ms, told.ms));
        }

        return result;
    }

    /**
     * Of the video, for a failure: the first told and each change after, the last MAX_FAILURE_STATUSES, each with how long ago it was
     * told. Null for none (e.g. not streamed by SABR).
     */
    @Nullable
    static synchronized JSONArray snapshot(@Nullable String videoId) {
        ArrayDeque<Told> statuses = videoId != null ? sStatuses.get(videoId) : null;

        if (statuses == null || statuses.isEmpty()) {
            return null;
        }

        List<Told> changes = new ArrayList<>();

        for (Told told : statuses) {
            if (changes.isEmpty() || !changes.get(changes.size() - 1).status.equals(told.status)) {
                changes.add(told);
            }
        }

        long now = sClock.now();
        JSONArray result = new JSONArray();

        try {
            for (Told told : changes.subList(Math.max(0, changes.size() - MAX_FAILURE_STATUSES), changes.size())) {
                JSONObject item = new JSONObject();
                item.put("status", told.status);
                item.put("msAgo", Math.max(0, now - told.ms));
                result.put(item);
            }
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }

        return result;
    }

    @VisibleForTesting
    public static synchronized void resetForTesting(@Nullable VideoStarts.Clock clock) {
        sStatuses.clear();
        sClock = clock != null ? clock : SystemClock::elapsedRealtime;
    }
}
