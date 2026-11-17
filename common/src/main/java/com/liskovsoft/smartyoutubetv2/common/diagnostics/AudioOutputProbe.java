package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * What a refused AudioTrack leaves to tell (see ErrorFixerController): whether the device takes a new track of 44.1 kHz and of 48 kHz
 * now, whether its output is broken (it gives no buffer size for them), how many players every app has, and how long ago an output
 * came or went (e.g. the TV or the receiver switched off and on). A Shield refused every 44.1 kHz track, a new process's first one too,
 * till the device was restarted (FARAJV5R #40–#41, 9M6NBWN7): the app held none, and nothing told whether a 48 kHz one (Opus) would
 * have played, whether another app filled the mixer, or what it started with.
 */
public final class AudioOutputProbe {
    /**
     * AAC's and Opus's, as YouTube serves them
     */
    private static final int[] SAMPLE_RATES = {44_100, 48_000};
    /**
     * The outputs there are, by ID (see AudioDeviceInfo#getId): one added or removed is a change
     */
    private static final Set<Integer> sOutputIds = new HashSet<>();
    /**
     * When the outputs began to be watched, and when they last changed (elapsed realtime), -1 for not yet
     */
    private static long sWatchedSinceMs = -1;
    private static long sChangedMs = -1;

    private AudioOutputProbe() {
    }

    /**
     * The outputs are watched from now on, for when one last came or went. Once a process.
     */
    public static synchronized void init(Context context) {
        if (sWatchedSinceMs >= 0 || context == null) {
            return;
        }

        AudioManager audioManager = (AudioManager) context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);

        if (audioManager == null) {
            return;
        }

        try {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                sOutputIds.add(device.getId());
            }

            // Told the outputs there are at once: no change, they're known
            audioManager.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
                    onOutputs(addedDevices, true);
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
                    onOutputs(removedDevices, false);
                }
            }, new Handler(Looper.getMainLooper()));
            sWatchedSinceMs = SystemClock.elapsedRealtime();
        } catch (RuntimeException e) {
            // Diagnostics only: unwatched, the changes are unknown
            android.util.Log.e("AudioOutputProbe", "Can't watch the audio outputs: " + e);
        }
    }

    private static synchronized void onOutputs(@Nullable AudioDeviceInfo[] devices, boolean isAdded) {
        if (devices == null) {
            return;
        }

        boolean isChanged = false;

        for (AudioDeviceInfo device : devices) {
            if (!device.isSink()) {
                continue;
            }

            isChanged |= isAdded ? sOutputIds.add(device.getId()) : sOutputIds.remove(device.getId());
        }

        if (isChanged) {
            sChangedMs = SystemClock.elapsedRealtime();
        }
    }

    /**
     * Now: e.g. {"minBuffer44100": 7056, "track44100": "refused", "minBuffer48000": 7680, "track48000": "ok", "players": 3,
     * "outputChangeMsAgo": 5200, "outputsWatchedMs": 123000}. A track is "ok", "refused", or the class of what its making threw. The
     * players are every app's (Android 8+). The change is null with none since the outputs were watched. A part that can't be read is
     * {"error": "…"}, the rest is still there.
     */
    public static JSONObject probe(@Nullable Context context) {
        JSONObject result = new JSONObject();

        try {
            for (int sampleRate : SAMPLE_RATES) {
                int minBufferBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT);
                // Negative: the device's output can't say (an error)
                result.put("minBuffer" + sampleRate, minBufferBytes);
                result.put("track" + sampleRate, tryTrack(sampleRate, minBufferBytes));
            }

            AudioManager audioManager = context != null ? (AudioManager) context.getSystemService(Context.AUDIO_SERVICE) : null;

            if (VERSION.SDK_INT >= 26 && audioManager != null) {
                result.put("players", audioManager.getActivePlaybackConfigurations().size());
            }

            synchronized (AudioOutputProbe.class) {
                long nowMs = SystemClock.elapsedRealtime();
                result.put("outputChangeMsAgo", sChangedMs >= 0 ? nowMs - sChangedMs : JSONObject.NULL);
                result.put("outputsWatchedMs", sWatchedSinceMs >= 0 ? nowMs - sWatchedSinceMs : JSONObject.NULL);
            }
        } catch (JSONException | RuntimeException e) {
            try {
                result.put("error", e.getClass().getSimpleName() + ": " + PlayerFailure.scrub(e.getMessage()));
            } catch (JSONException ignored) {
                // A name and a string
            }
        }

        return result;
    }

    /**
     * A stereo 16-bit track of the rate made and released at once, as the player makes it: 250 ms of buffer, or the device's least if
     * that's more (see DefaultAudioSink). Never played.
     */
    private static String tryTrack(int sampleRate, int minBufferBytes) {
        // 250 ms of 2 channels of 2 bytes
        int bufferBytes = Math.max(minBufferBytes, sampleRate);
        AudioTrack track = null;

        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build();
            AudioFormat format = new AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            track = new AudioTrack(attributes, format, bufferBytes, AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);

            return track.getState() == AudioTrack.STATE_INITIALIZED ? "ok" : "refused";
        } catch (RuntimeException e) {
            return e.getClass().getSimpleName();
        } finally {
            if (track != null) {
                track.release();
            }
        }
    }
}
