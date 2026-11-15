package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build.VERSION;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.smartyoutubetv2.common.prefs.DebuggingData;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The app dying, to the player failure log: a crash when it happens, and on Android 11+ the ANRs, native crashes and kills for
 * memory Android kept, found on the next start
 */
public final class AppExits {
    public static final String KIND_CRASH = "crash";
    public static final String KIND_APP_EXIT = "app_exit";
    private static final int MAX_EXITS = 16;
    /**
     * Apart at most: processes that went together
     */
    private static final long SAME_EXIT_MS = 5_000;
    /**
     * Of a thread of an ANR's traces, the main one or the one holding the lock it waits for. A longer one keeps its first and last lines:
     * the frames that tell how the work got there (e.g. a pool's rejection handler) are deep, 30 lines left them out.
     */
    static final int MAX_TRACE_LINES = 100;
    static final int TRACE_TAIL_LINES = 30;
    private static final int MAX_TRACE_BYTES = 256 * 1024;
    /**
     * "- waiting to lock <0x053c914c> (a java.lang.Object) held by thread 37": the thread's tid
     */
    private static final Pattern LOCK_HOLDER = Pattern.compile("held by thread (\\d+)");
    /**
     * Where the newest exit recorded is kept (see getLastSeenTs)
     */
    private static final String PREFS = "app_exits";
    private static final String KEY_LAST_SEEN_TS = "last_seen_ts";

    private AppExits() {
    }

    /**
     * From the app's uncaught exception handler: written before it returns, with what's still in memory. Never throws.
     */
    public static void recordCrash(Context context, Thread thread, Throwable error) {
        try {
            FormatAttempts.flush(context, true);
            PlayerFailure.create(context, KIND_CRASH)
                    .videoId(FormatAttempts.getVideoId())
                    .error(error)
                    .detail("thread", thread.getName())
                    .recordNow();
        } catch (Throwable e) {
            // The crash goes on as it would
        }
    }

    /**
     * The exits since the last start (Android 11+), each once. Not on the main thread: an ANR's traces are read.
     */
    public static synchronized void recordNew(Context context) {
        if (VERSION.SDK_INT < 30) {
            return;
        }

        try {
            recordNewExits(context);
        } catch (RuntimeException e) {
            android.util.Log.e("PlayerFailure", "Can't read the app exits: " + e);
        }
    }

    @RequiresApi(30)
    private static void recordNewExits(Context context) {
        ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);

        if (activityManager == null) {
            return;
        }

        long lastSeen = getLastSeenTs(context);
        long newest = lastSeen;
        List<ApplicationExitInfo> exits = activityManager.getHistoricalProcessExitReasons(context.getPackageName(), 0, MAX_EXITS);

        // Newest first: recorded oldest first
        for (int i = exits.size() - 1; i >= 0; i--) {
            ApplicationExitInfo exit = exits.get(i);

            if (exit.getTimestamp() <= lastSeen) {
                continue;
            }

            newest = Math.max(newest, exit.getTimestamp());
            String reason = getReasonName(exit.getReason());

            // A Java crash was recorded when it happened (see recordCrash); the user closing it or the app updating isn't a failure
            if (reason == null) {
                continue;
            }

            // Android closing a cached app to make room: nobody was using it. With the low memory killer's reports, its kills are
            // low_memory ones.
            if (isReclaimed(exit) || isOfMainExit(context, exit, exits)) {
                continue;
            }

            PlayerFailure failure = PlayerFailure.create(context, KIND_APP_EXIT)
                    .at(exit.getTimestamp())
                    .withoutState()
                    .detail("reason", reason)
                    .detail("description", Scrubber.scrub(exit.getDescription()))
                    .detail("process", exit.getProcessName())
                    .detail("foreground", exit.getImportance() <= RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
                    // E.g. 400 cached, 125 a foreground service (background playback, the remote control)
                    .detail("importance", exit.getImportance())
                    // The signal of a "signaled" one (9: killed)
                    .detail("status", exit.getStatus())
                    // Without it the low memory killer's kills are "signaled" ones
                    .detail("lowMemoryKillReport", ActivityManager.isLowMemoryKillReportSupported())
                    .detail("pssMb", exit.getPss() / 1024)
                    .detail("rssMb", exit.getRss() / 1024);

            if (exit.getReason() == ApplicationExitInfo.REASON_ANR) {
                addTraces(failure, exit);
            }

            // What it was doing (e.g. the remote control's service running, a video playing): Android doesn't say
            JSONObject lastState = LastState.find(context, exit.getPid(), exit.getTimestamp());

            if (lastState != null) {
                failure.detail("lastState", lastState);
            }

            failure.record();
        }

        if (newest > lastSeen) {
            setLastSeenTs(context, newest);
        }
    }

    /**
     * The newest exit recorded, kept in its own file and written before recordNew returns. DebuggingData saved it on the main thread:
     * a process whose main thread hung (an ANR) never did, and the next one recorded the same exits again. Its value is the one until
     * this file has one.
     */
    private static long getLastSeenTs(Context context) {
        SharedPreferences prefs = getPrefs(context);

        return prefs.contains(KEY_LAST_SEEN_TS) ? prefs.getLong(KEY_LAST_SEEN_TS, 0) : DebuggingData.instance(context).getLastAppExitTs();
    }

    private static void setLastSeenTs(Context context, long ts) {
        // On the disk now: the process may hang or die right after
        getPrefs(context).edit().putLong(KEY_LAST_SEEN_TS, ts).commit();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @VisibleForTesting
    static void resetForTesting(Context context) {
        getPrefs(context).edit().clear().commit();
    }

    /**
     * A WebView's sandboxed process that went with the app's main one: one exit, recorded for the main one. Alone, its renderer failed.
     */
    @RequiresApi(30)
    private static boolean isOfMainExit(Context context, ApplicationExitInfo exit, List<ApplicationExitInfo> exits) {
        String process = exit.getProcessName();

        if (process == null || !process.contains(":sandboxed_process")) {
            return false;
        }

        for (ApplicationExitInfo other : exits) {
            if (context.getPackageName().equals(other.getProcessName()) && Math.abs(other.getTimestamp() - exit.getTimestamp()) <= SAME_EXIT_MS) {
                return true;
            }
        }

        return false;
    }

    /**
     * Android took it back while it was cached: killed by a signal, or by the low memory killer where it reports its kills
     */
    @RequiresApi(30)
    private static boolean isReclaimed(ApplicationExitInfo exit) {
        return (exit.getReason() == ApplicationExitInfo.REASON_SIGNALED || exit.getReason() == ApplicationExitInfo.REASON_LOW_MEMORY)
                && exit.getImportance() >= RunningAppProcessInfo.IMPORTANCE_CACHED;
    }

    @Nullable
    private static String getReasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_ANR:
                return "anr";
            case ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "crash_native";
            case ApplicationExitInfo.REASON_LOW_MEMORY:
                return "low_memory";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "excessive_resource_usage";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "initialization_failure";
            case ApplicationExitInfo.REASON_SIGNALED:
                return "signaled";
            default:
                return null;
        }
    }

    /**
     * The main thread of the traces Android took, and the thread holding the lock it waits for, if it does (e.g. a poToken being made,
     * which needs the main thread back: a deadlock)
     */
    @RequiresApi(30)
    private static void addTraces(PlayerFailure failure, ApplicationExitInfo exit) {
        List<List<String>> threads = readThreads(exit);
        List<String> main = findThread(threads, "\"main\"");

        if (main == null) {
            return;
        }

        failure.detail("mainThread", toJson(main));

        String holderTid = findLockHolderTid(main);
        List<String> holder = holderTid != null ? findThread(threads, " tid=" + holderTid + " ") : null;

        if (holder != null) {
            failure.detail("lockHolderThread", toJson(holder));
        }
    }

    /**
     * Each thread of the traces, its lines from its header (e.g. "main" prio=5 tid=1 Blocked) to the blank line after it. Empty when
     * there are none.
     */
    @RequiresApi(30)
    private static List<List<String>> readThreads(ApplicationExitInfo exit) {
        List<List<String>> threads = new ArrayList<>();

        try (InputStream traces = exit.getTraceInputStream()) {
            if (traces == null) {
                return threads;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(traces, StandardCharsets.UTF_8));
            List<String> thread = null;
            int bytes = 0;
            String line;

            while ((line = reader.readLine()) != null && bytes < MAX_TRACE_BYTES) {
                bytes += line.length() + 1;

                if (line.startsWith("\"")) {
                    thread = new ArrayList<>();
                    threads.add(thread);
                } else if (line.trim().isEmpty()) {
                    thread = null;
                }

                if (thread != null) {
                    thread.add(line);
                }
            }
        } catch (IOException e) {
            // What was read
        }

        return threads;
    }

    /**
     * @param headerPart in the header line of the thread, e.g. "main" or " tid=37 "
     */
    @Nullable
    static List<String> findThread(List<List<String>> threads, String headerPart) {
        for (List<String> thread : threads) {
            String header = thread.get(0) + " ";

            if (header.startsWith(headerPart) || (!headerPart.startsWith("\"") && header.contains(headerPart))) {
                return thread;
            }
        }

        return null;
    }

    /**
     * The tid of the thread that holds the lock the thread waits for, null when it doesn't wait for one
     */
    @Nullable
    static String findLockHolderTid(List<String> thread) {
        for (String line : thread) {
            Matcher matcher = LOCK_HOLDER.matcher(line);

            if (matcher.find()) {
                return matcher.group(1);
            }
        }

        return null;
    }

    /**
     * Scrubbed, its first and last lines when it's longer than MAX_TRACE_LINES, with how many were left out between them
     */
    static JSONArray toJson(List<String> thread) {
        JSONArray result = new JSONArray();
        int size = thread.size();

        if (size <= MAX_TRACE_LINES) {
            for (String line : thread) {
                result.put(Scrubber.scrub(line));
            }

            return result;
        }

        int head = MAX_TRACE_LINES - TRACE_TAIL_LINES - 1;

        for (int i = 0; i < head; i++) {
            result.put(Scrubber.scrub(thread.get(i)));
        }

        result.put("  ... " + (size - head - TRACE_TAIL_LINES) + " lines left out");

        for (int i = size - TRACE_TAIL_LINES; i < size; i++) {
            result.put(Scrubber.scrub(thread.get(i)));
        }

        return result;
    }
}
