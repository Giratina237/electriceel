package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How long something played at each playback speed (see PlaybackSpeed), in the order each speed was first played at
 */
final class SpeedTimes {
    private final Map<Float, Long> mPlayedMs = new LinkedHashMap<>();

    /**
     * Played this long at the speed
     */
    void add(float speed, long playedMs) {
        if (playedMs <= 0) {
            return;
        }

        Long before = mPlayedMs.get(speed);
        mPlayedMs.put(speed, before != null ? before + playedMs : playedMs);
    }

    /**
     * [{"speed": 1.5, "playedMs": 61000}, …], with the time still going on at the current speed (see PlaybackSpeed#get)
     *
     * @param goingOnMs played since the last time added, 0 when it doesn't play
     */
    JSONArray toJson(long goingOnMs) throws JSONException {
        Map<Float, Long> playedMs = new LinkedHashMap<>(mPlayedMs);

        if (goingOnMs > 0) {
            float speed = PlaybackSpeed.get();
            Long before = playedMs.get(speed);
            playedMs.put(speed, before != null ? before + goingOnMs : goingOnMs);
        }

        JSONArray result = new JSONArray();

        for (Map.Entry<Float, Long> entry : playedMs.entrySet()) {
            JSONObject item = new JSONObject();
            // A float's double is 1.100000023841858 for 1.1: its own decimal form
            item.put("speed", Double.parseDouble(Float.toString(entry.getKey())));
            item.put("playedMs", entry.getValue().longValue());
            result.put(item);
        }

        return result;
    }
}
