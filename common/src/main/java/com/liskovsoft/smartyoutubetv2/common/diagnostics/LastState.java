package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.app.ApplicationStartInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build.VERSION;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the app was doing, kept for an app exit found on the next start to tell (see AppExits): a kill leaves no record of its own.
 * Saved when the process starts (with what started it, Android 15+), when the activity on top changes its state or goes in or out of
 * picture-in-picture, when a foreground service (e.g. the remote control's) starts or stops, when the player starts or stops playing,
 * and at most every 30 s while a video is loaded. A process killed before any activity showed (e.g. started for a job) says "none".
 * No video id: the exit's record is sent without one. The process before's is kept too: an exit is of a process before this one.
 * Used on the main thread.
 */
public final class LastState {
    public static final String SERVICE_REMOTE_CONTROL = "remote_control";
    private static final String PREFS = "diagnostics_last_state";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_PREVIOUS = "previous";
    /**
     * While a video is loaded and nothing changes: what it did minutes before the exit is what it did at it
     */
    @VisibleForTesting
    static final long MIN_INTERVAL_MS = 30_000;
    private static final Set<String> sServices = new LinkedHashSet<>();
    private static boolean sHasPlayer;
    private static boolean sIsPlaying;
    /**
     * The activity on top and its state, e.g. "ModernPlaybackActivity resumed". Null before one is created.
     */
    @Nullable
    private static String sActivity;
    @Nullable
    private static String sActivityName;
    private static boolean sIsPip;
    /**
     * What started the process (Android 15+), e.g. "launcher" or "job". Null when not known.
     */
    @Nullable
    private static String sStartReason;
    private static long sSavedMs = -1;
    @VisibleForTesting
    static VideoStarts.Clock sClock = SystemClock::elapsedRealtime;
    @VisibleForTesting
    static VideoStarts.Clock sWallClock = System::currentTimeMillis;
    @VisibleForTesting
    static PidSource sPid = Process::myPid;

    interface PidSource {
        int get();
    }

    private LastState() {
    }

    /**
     * When the process starts (see MainApplication): then each change of the activity on top
     */
    public static void onProcessStart(Application app) {
        app.registerActivityLifecycleCallbacks(new ActivityCallbacks(app));
        save(app);

        if (VERSION.SDK_INT >= 35) {
            // A binder call, not on the main thread
            RxHelper.runAsync(() -> {
                String reason = findStartReason(app);

                if (reason != null) {
                    Utils.post(() -> {
                        sStartReason = reason;
                        save(app);
                    });
                }
            });
        }
    }

    /**
     * The player went in or out of picture-in-picture (see PlaybackActivity)
     */
    public static void onPictureInPicture(Context context, boolean isPip) {
        if (isPip != sIsPip) {
            sIsPip = isPip;
            save(context);
        }
    }

    /**
     * The state of the activity on top. One that leaves (e.g. stopped once another is resumed) isn't the one on top anymore.
     */
    @VisibleForTesting
    static void onActivity(Context context, String name, String state, boolean isLeaving) {
        if (isLeaving && !name.equals(sActivityName)) {
            return;
        }

        sActivityName = name;
        sActivity = name + " " + state;
        save(context);
    }

    public static void onServiceStarted(Context context, String service) {
        sServices.add(service);
        save(context);
    }

    public static void onServiceStopped(Context context, String service) {
        sServices.remove(service);
        save(context);
    }

    /**
     * At each check of the playback (see ExoPlayerController#checkHealth), and when the player is released
     *
     * @param hasPlayer a video is loaded
     */
    public static void onPlayer(Context context, boolean hasPlayer, boolean isPlaying) {
        boolean isChanged = hasPlayer != sHasPlayer || isPlaying != sIsPlaying;
        sHasPlayer = hasPlayer;
        sIsPlaying = isPlaying;

        if (isChanged || sSavedMs < 0 || sClock.now() - sSavedMs >= MIN_INTERVAL_MS) {
            save(context);
        }
    }

    private static void save(Context context) {
        try {
            JSONObject state = new JSONObject();
            state.put("ts", sWallClock.now());
            state.put("pid", sPid.get());
            state.put("services", new JSONArray(sServices));
            state.put("player", sHasPlayer);
            state.put("playing", sIsPlaying);
            state.put("activity", sActivity != null ? sActivity : "none");

            if (sIsPip) {
                state.put("pip", true);
            }

            if (sStartReason != null) {
                state.put("startReason", sStartReason);
            }

            Runtime runtime = Runtime.getRuntime();
            state.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

            if (activityManager != null) {
                ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
                activityManager.getMemoryInfo(memory);
                state.put("availMemMb", memory.availMem / (1024 * 1024));
            }

            // E.g. 125 a foreground service (the remote control), 400 cached
            RunningAppProcessInfo process = new RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(process);
            state.put("importance", process.importance);

            SharedPreferences prefs = getPrefs(context);
            SharedPreferences.Editor editor = prefs.edit();
            JSONObject current = parse(prefs.getString(KEY_CURRENT, null));

            if (current != null && current.optInt("pid") != sPid.get()) {
                editor.putString(KEY_PREVIOUS, current.toString());
            }

            editor.putString(KEY_CURRENT, state.toString()).apply();
            sSavedMs = sClock.now();
        } catch (JSONException | RuntimeException e) {
            android.util.Log.e("LastState", "Can't save: " + e);
        }
    }

    /**
     * Of the process that exited, saved before it did: however long before, what changes is saved, so it's still what it did (e.g. idle
     * for hours with the remote control's service running). Null with none.
     *
     * @param exitTs wall clock
     */
    @Nullable
    static JSONObject find(Context context, int pid, long exitTs) {
        SharedPreferences prefs = getPrefs(context);

        for (String key : new String[]{KEY_CURRENT, KEY_PREVIOUS}) {
            JSONObject state = parse(prefs.getString(key, null));

            if (state == null || state.optInt("pid", -1) != pid) {
                continue;
            }

            long agoMs = exitTs - state.optLong("ts");

            if (agoMs < 0) {
                continue;
            }

            state.remove("ts");
            state.remove("pid");

            try {
                state.put("agoMs", agoMs);
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }

            return state;
        }

        return null;
    }

    @Nullable
    private static JSONObject parse(@Nullable String json) {
        if (json == null) {
            return null;
        }

        try {
            return new JSONObject(json);
        } catch (JSONException e) {
            return null;
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * What started this process, e.g. "launcher" or "job". Null when it isn't known (before Android 15).
     */
    @Nullable
    private static String findStartReason(Context context) {
        if (VERSION.SDK_INT < 35) {
            return null;
        }

        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        if (activityManager == null) {
            return null;
        }

        try {
            for (ApplicationStartInfo info : activityManager.getHistoricalProcessStartReasons(1)) {
                if (info.getPid() == sPid.get()) {
                    return getStartReasonName(info.getReason());
                }
            }
        } catch (RuntimeException e) {
            // Not known
        }

        return null;
    }

    @RequiresApi(35)
    private static String getStartReasonName(int reason) {
        switch (reason) {
            case ApplicationStartInfo.START_REASON_LAUNCHER:
                return "launcher";
            case ApplicationStartInfo.START_REASON_LAUNCHER_RECENTS:
                return "launcher_recents";
            case ApplicationStartInfo.START_REASON_START_ACTIVITY:
                return "start_activity";
            case ApplicationStartInfo.START_REASON_SERVICE:
                return "service";
            case ApplicationStartInfo.START_REASON_BROADCAST:
                return "broadcast";
            case ApplicationStartInfo.START_REASON_JOB:
                return "job";
            case ApplicationStartInfo.START_REASON_CONTENT_PROVIDER:
                return "content_provider";
            case ApplicationStartInfo.START_REASON_BOOT_COMPLETE:
                return "boot_complete";
            case ApplicationStartInfo.START_REASON_ALARM:
                return "alarm";
            case ApplicationStartInfo.START_REASON_PUSH:
                return "push";
            case ApplicationStartInfo.START_REASON_BACKUP:
                return "backup";
            default:
                return "other";
        }
    }

    /**
     * The states that tell what the user saw: not created or started, which come right before resumed
     */
    private static final class ActivityCallbacks implements Application.ActivityLifecycleCallbacks {
        private final Context mContext;

        ActivityCallbacks(Context context) {
            mContext = context.getApplicationContext();
        }

        @Override
        public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        }

        @Override
        public void onActivityStarted(@NonNull Activity activity) {
        }

        @Override
        public void onActivityResumed(@NonNull Activity activity) {
            onActivity(mContext, activity.getClass().getSimpleName(), "resumed", false);
        }

        @Override
        public void onActivityPaused(@NonNull Activity activity) {
            onActivity(mContext, activity.getClass().getSimpleName(), "paused", true);
        }

        @Override
        public void onActivityStopped(@NonNull Activity activity) {
            onActivity(mContext, activity.getClass().getSimpleName(), "stopped", true);
        }

        @Override
        public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
        }

        @Override
        public void onActivityDestroyed(@NonNull Activity activity) {
            onActivity(mContext, activity.getClass().getSimpleName(), "destroyed", true);
        }
    }

    @VisibleForTesting
    static void resetForTesting(Context context) {
        sServices.clear();
        sHasPlayer = false;
        sIsPlaying = false;
        sActivity = null;
        sActivityName = null;
        sIsPip = false;
        sStartReason = null;
        sSavedMs = -1;
        sClock = SystemClock::elapsedRealtime;
        sWallClock = System::currentTimeMillis;
        sPid = Process::myPid;
        getPrefs(context).edit().clear().commit();
    }
}
