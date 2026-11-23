package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ApplicationExitInfo;
import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.DebuggingData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AppExitsTest {
    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();
    private PlayerFailureLog mLog;
    private int mSavedEngine;
    private long mSavedLastExitTs;

    @Before
    public void setUp() {
        mLog = PlayerFailureLog.useForTesting(mFolder.getRoot());
        FormatAttempts.resetForTesting();
        // Cronet can't load under Robolectric
        mSavedEngine = PlayerTweaksData.instance(getContext()).getPlayerDataSource();
        // The exits seen are kept between the tests: each sees its own
        mSavedLastExitTs = DebuggingData.instance(getContext()).getLastAppExitTs();
        DebuggingData.instance(getContext()).setLastAppExitTs(0);
        AppExits.resetForTesting(getContext());
        PlayerCommands.onNewVideo(null);
        PlayerTweaksData.instance(getContext()).setPlayerDataSource(PlayerTweaksData.PLAYER_DATA_SOURCE_OKHTTP);
    }

    @After
    public void tearDown() {
        LastState.resetForTesting(getContext());
        FormatAttempts.resetForTesting();
        PlayerFailureLog.useForTesting(null);
        PlayerTweaksData.instance(getContext()).setPlayerDataSource(mSavedEngine);
        DebuggingData.instance(getContext()).setLastAppExitTs(mSavedLastExitTs);
        AppExits.resetForTesting(getContext());
        PlayerCommands.onNewVideo(null);
    }

    /**
     * On the disk before the handler returns (the app dies next), with the video playing
     */
    @Test
    public void aCrashIsWrittenRightAway() throws Exception {
        FormatAttempts.onNewVideo(getContext(), "playing");

        AppExits.recordCrash(getContext(), new Thread("worker"), new IllegalStateException("Boom"));

        String written = new String(Files.readAllBytes(new File(mFolder.getRoot(), PlayerFailureLog.CURRENT).toPath()), StandardCharsets.UTF_8);
        assertTrue(written, written.contains("\"kind\":\"crash\""));
        assertTrue(written, written.contains("\"videoId\":\"playing\""));
        assertTrue(written, written.contains("\"thread\":\"worker\""));
        assertTrue(written, written.contains("Boom"));
    }

    /**
     * Past the root cause's first frames, which may all be a library's (53M2PA8E: OkHttp's call cancelled): the bottom of its stack,
     * where the thread ran it from, and the top of what it was thrown as
     */
    @Test
    public void aCrashKeepsHowItCameToCrash() throws Exception {
        IOException root = new IOException("Canceled");
        root.setStackTrace(frames("okhttp3.RealCall", 40));
        IllegalStateException error = new IllegalStateException(root);
        error.setStackTrace(frames("io.reactivex.plugins.RxJavaPlugins", 15));

        AppExits.recordCrash(getContext(), new Thread("pool-2-thread-6"), error);

        String[] lines = new String(Files.readAllBytes(new File(mFolder.getRoot(), PlayerFailureLog.CURRENT).toPath()),
                StandardCharsets.UTF_8).trim().split("\n");
        JSONArray stack = new JSONObject(lines[lines.length - 1]).getJSONObject("error").getJSONArray("stack");
        List<String> expected = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            expected.add(frame("okhttp3.RealCall", i));
        }

        expected.add("... 12 more");

        for (int i = 32; i < 40; i++) {
            expected.add(frame("okhttp3.RealCall", i));
        }

        expected.add("--- thrown as IllegalStateException");

        for (int i = 0; i < 12; i++) {
            expected.add(frame("io.reactivex.plugins.RxJavaPlugins", i));
        }

        List<String> actual = new ArrayList<>();

        for (int i = 0; i < stack.length(); i++) {
            actual.add(stack.getString(i));
        }

        assertEquals(expected, actual);
    }

    private static StackTraceElement[] frames(String className, int count) {
        StackTraceElement[] result = new StackTraceElement[count];

        for (int i = 0; i < count; i++) {
            result[i] = new StackTraceElement(className, "m" + i, "F.java", i + 1);
        }

        return result;
    }

    private static String frame(String className, int index) {
        return new StackTraceElement(className, "m" + index, "F.java", index + 1).toString();
    }

    @Test
    public void noExitsBeforeAndroid11() {
        // Robolectric runs as Android 9: nothing read, nothing thrown
        AppExits.recordNew(getContext());
    }

    /**
     * Killed in the background, e.g. by the low memory killer of a device that doesn't report its kills: how important the
     * process was, the signal, and whether the device reports them
     */
    @Test
    @Config(sdk = 30)
    public void aSignaledExitHasItsImportanceAndSignal() throws Exception {
        ActivityManager activityManager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(activityManager).addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setReason(ApplicationExitInfo.REASON_SIGNALED)
                .setStatus(9)
                .setImportance(RunningAppProcessInfo.IMPORTANCE_SERVICE)
                .setProcessName(getContext().getPackageName())
                .build());

        AppExits.recordNew(getContext());

        JSONObject details = mLog.readAll().get(0).getJSONObject("details");
        assertEquals("signaled", details.getString("reason"));
        assertFalse(details.getBoolean("foreground"));
        assertEquals(RunningAppProcessInfo.IMPORTANCE_SERVICE, details.getInt("importance"));
        assertEquals(9, details.getInt("status"));
        assertEquals(ActivityManager.isLowMemoryKillReportSupported(), details.getBoolean("lowMemoryKillReport"));
    }

    /**
     * The WebView's process, killed with the app's: one exit
     */
    @Test
    @Config(sdk = 30)
    public void aSandboxedProcessThatWentWithTheAppIsNotRecordedToo() throws Exception {
        long now = System.currentTimeMillis() - 120_000;
        addExit("com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0", now);
        addExit(getContext().getPackageName(), now + 25);

        AppExits.recordNew(getContext());

        List<JSONObject> events = mLog.readAll();
        assertEquals(1, events.size());
        assertEquals(getContext().getPackageName(), events.get(0).getJSONObject("details").getString("process"));
    }

    /**
     * Alone, the WebView's renderer failed
     */
    @Test
    @Config(sdk = 30)
    public void aSandboxedProcessThatWentAloneIsRecorded() throws Exception {
        long now = System.currentTimeMillis() - 120_000;
        String sandbox = "com.google.android.webview:sandboxed_process0:org.chromium.content.app.SandboxedProcessService0:0";
        addExit(sandbox, now);
        addExit(getContext().getPackageName(), now + 60_000);

        AppExits.recordNew(getContext());

        List<JSONObject> events = mLog.readAll();
        assertEquals(2, events.size());
        assertEquals(sandbox, events.get(0).getJSONObject("details").getString("process"));
    }

    private void addExit(String process, long timestamp) {
        ActivityManager activityManager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(activityManager).addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(timestamp)
                .setReason(ApplicationExitInfo.REASON_SIGNALED)
                .setStatus(9)
                .setImportance(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
                .setProcessName(process)
                .build());
    }

    /**
     * Android closing it to make room: not a failure
     */
    @Test
    @Config(sdk = 30)
    public void aCachedAppKilledIsNotRecorded() throws Exception {
        ActivityManager activityManager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(activityManager).addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setReason(ApplicationExitInfo.REASON_SIGNALED)
                .setStatus(9)
                .setImportance(RunningAppProcessInfo.IMPORTANCE_CACHED)
                .setProcessName(getContext().getPackageName())
                .build());

        AppExits.recordNew(getContext());

        assertTrue(mLog.readAll().isEmpty());
    }

    /**
     * Where the low memory killer reports its kills, they're low_memory ones: of a cached app, the same as a signaled one
     */
    @Test
    @Config(sdk = 30)
    public void aCachedAppTheLowMemoryKillerKilledIsNotRecorded() throws Exception {
        ActivityManager activityManager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(activityManager).addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
                .setImportance(RunningAppProcessInfo.IMPORTANCE_CACHED)
                .setProcessName(getContext().getPackageName())
                .build());

        AppExits.recordNew(getContext());

        assertTrue(mLog.readAll().isEmpty());
    }

    /**
     * Killed for memory while it was used (e.g. playing in the background): a failure
     */
    @Test
    @Config(sdk = 30)
    public void aLowMemoryKillWhileUsedIsRecorded() throws Exception {
        ActivityManager activityManager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(activityManager).addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setReason(ApplicationExitInfo.REASON_LOW_MEMORY)
                .setImportance(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
                .setProcessName(getContext().getPackageName())
                .build());

        AppExits.recordNew(getContext());

        assertEquals("low_memory", mLog.readAll().get(0).getJSONObject("details").getString("reason"));
    }

    /**
     * Killed while the remote control's service ran: what it was doing comes with it, Android doesn't say
     */
    @Test
    @Config(sdk = 30)
    public void anExitHasWhatItsProcessWasDoing() throws Exception {
        long now = System.currentTimeMillis();
        LastState.sPid = () -> 4242;
        LastState.sWallClock = () -> now - 5_000;
        LastState.onServiceStarted(getContext(), LastState.SERVICE_REMOTE_CONTROL);
        ActivityManager activityManager = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        shadowOf(activityManager).addApplicationExitInfo(ApplicationExitInfoBuilder.newBuilder()
                .setTimestamp(now)
                .setPid(4242)
                .setReason(ApplicationExitInfo.REASON_SIGNALED)
                .setStatus(9)
                .setImportance(RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
                .setProcessName(getContext().getPackageName())
                .build());

        AppExits.recordNew(getContext());

        JSONObject lastState = mLog.readAll().get(0).getJSONObject("details").getJSONObject("lastState");
        assertEquals(LastState.SERVICE_REMOTE_CONTROL, lastState.getJSONArray("services").getString(0));
        assertEquals(5_000, lastState.getLong("agoMs"));
    }

    /**
     * Found on the next start once: the exit seen is on the disk before recordNew returns, not saved later on the main thread. A process
     * whose main thread hung never saved it, and the next one recorded the same ANR again.
     */
    @Test
    @Config(sdk = 30)
    public void anExitIsRecordedOnceAcrossStarts() throws Exception {
        addExit(getContext().getPackageName(), System.currentTimeMillis() - 60_000);

        AppExits.recordNew(getContext());
        // The settings' save never ran (the main thread hung): the next start finds the old value there
        Utils.sHandler.removeCallbacksAndMessages(null);
        DebuggingData.instance(getContext()).setLastAppExitTs(0);
        AppExits.recordNew(getContext());

        assertEquals(1, mLog.readAll().size());
    }

    /**
     * Until its own file has one, the exit seen DebuggingData kept: an update records nothing twice
     */
    @Test
    @Config(sdk = 30)
    public void theExitSeenBeforeIsStillSeen() throws Exception {
        long exitTs = System.currentTimeMillis() - 60_000;
        addExit(getContext().getPackageName(), exitTs);
        DebuggingData.instance(getContext()).setLastAppExitTs(exitTs);

        AppExits.recordNew(getContext());

        assertTrue(mLog.readAll().isEmpty());
    }

    /**
     * What the new process told the player isn't what the one that exited did
     */
    @Test
    @Config(sdk = 30)
    public void anExitHasNoCommandsOfThisProcess() throws Exception {
        PlayerCommands.onNewVideo("this process's");
        PlayerCommands.onSeek(PlayerCommands.SOURCE_APP, 0);
        addExit(getContext().getPackageName(), System.currentTimeMillis() - 60_000);

        AppExits.recordNew(getContext());

        assertFalse(mLog.readAll().get(0).has("commands"));
    }

    @Test
    public void aShortThreadIsKeptWhole() throws Exception {
        List<String> thread = thread(40);

        JSONArray json = AppExits.toJson(thread);

        assertEquals(40, json.length());
        assertEquals(thread.get(39), json.getString(39));
    }

    /**
     * Its deep frames tell how the work got there (e.g. a pool's rejection handler): the last ones are kept too
     */
    @Test
    public void aLongThreadKeepsItsFirstAndLastLines() throws Exception {
        List<String> thread = thread(150);

        JSONArray json = AppExits.toJson(thread);

        assertEquals(AppExits.MAX_TRACE_LINES, json.length());
        assertEquals(thread.get(0), json.getString(0));
        int head = AppExits.MAX_TRACE_LINES - AppExits.TRACE_TAIL_LINES - 1;
        assertEquals("  ... " + (150 - head - AppExits.TRACE_TAIL_LINES) + " lines left out", json.getString(head));
        assertEquals(thread.get(149), json.getString(AppExits.MAX_TRACE_LINES - 1));
    }

    /**
     * The main thread waits for a lock: the thread holding it is found by its tid
     */
    @Test
    public void theThreadHoldingTheLockIsFound() {
        List<String> main = Arrays.asList("\"main\" prio=5 tid=1 Blocked",
                "  at com.liskovsoft.youtubeapi.app.potokennp2.TvPoTokenProvider.reset(TvPoTokenProvider.kt:224)",
                "  - waiting to lock <0x053c914c> (a java.lang.Object) held by thread 37");
        List<String> holder = Arrays.asList("\"RxCachedThreadScheduler-3\" daemon prio=5 tid=37 Waiting",
                "  at com.liskovsoft.youtubeapi.app.potokennp2.misc.WebViewUtilKt.runOnMainThreadSync(WebViewUtil.kt:156)");
        List<String> other = Arrays.asList("\"Signal Catcher\" daemon prio=10 tid=3 Runnable");
        List<List<String>> threads = Arrays.asList(main, other, holder);

        assertEquals("37", AppExits.findLockHolderTid(main));
        assertEquals(main, AppExits.findThread(threads, "\"main\""));
        assertEquals(holder, AppExits.findThread(threads, " tid=37 "));
        assertEquals(other, AppExits.findThread(threads, " tid=3 "));
        assertNull(AppExits.findThread(threads, " tid=99 "));
        assertNull(AppExits.findLockHolderTid(holder));
    }

    private static List<String> thread(int lines) {
        List<String> thread = new ArrayList<>();
        thread.add("\"main\" prio=5 tid=1 Waiting");

        for (int i = 1; i < lines; i++) {
            thread.add("  at com.example.Frame.method" + i + "(Frame.java:" + i + ")");
        }

        return thread;
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
