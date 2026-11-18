package com.google.android.exoplayer2;

import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A playback thread stuck in a platform call (a codec or AudioTrack release) doesn't hang the app's thread with it (ExoPlayer 2.13.0
 * #4352 and #5887, backported to the vendored 2.10.6). Here, not with the vendored ExoPlayer: that module's tests don't compile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlaybackThreadTimeoutTest {
    private final CountDownLatch mUnblock = new CountDownLatch(1);
    private SimpleExoPlayer mPlayer;

    @Before
    public void setUp() {
        mPlayer = ExoPlayerFactory.newSimpleInstance(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        mUnblock.countDown();
        mPlayer.release();
    }

    @Test(timeout = 10_000)
    public void release_givesUpOnAStuckPlaybackThread() {
        blockPlaybackThread();
        long startMs = nowMs();

        mPlayer.release();

        long waitedMs = nowMs() - startMs;
        assertTrue("waited " + waitedMs, waitedMs >= 450 && waitedMs < 2_000);
    }

    @Test(timeout = 10_000)
    public void clearingTheSurface_givesUpOnAStuckPlaybackThread() {
        mPlayer.setVideoSurface(new Surface(new SurfaceTexture(0)));
        blockPlaybackThread();
        long startMs = nowMs();

        mPlayer.clearVideoSurface();

        long waitedMs = nowMs() - startMs;
        assertTrue("waited " + waitedMs, waitedMs >= 1_900 && waitedMs < 4_000);
    }

    @Test(timeout = 10_000)
    public void aTimedWait_throwsOnceItsTimeIsUp() throws InterruptedException {
        HandlerThread thread = new HandlerThread("PlaybackThreadTimeoutTest");
        thread.start();
        PlayerMessage message = new PlayerMessage(m -> {}, (type, payload) -> {}, Timeline.EMPTY, 0, new Handler(thread.getLooper()));
        message.send();
        long startMs = nowMs();

        try {
            message.blockUntilDelivered(100);
            fail("delivered");
        } catch (TimeoutException e) {
            long waitedMs = nowMs() - startMs;
            assertTrue("waited " + waitedMs, waitedMs >= 90 && waitedMs < 1_000);
        } finally {
            thread.quit();
        }
    }

    @Test(timeout = 10_000)
    public void aTimedWait_returnsOnceDelivered() throws InterruptedException, TimeoutException {
        HandlerThread thread = new HandlerThread("PlaybackThreadTimeoutTest");
        thread.start();
        PlayerMessage message = new PlayerMessage(
                m -> m.markAsProcessed(true), (type, payload) -> {}, Timeline.EMPTY, 0, new Handler(thread.getLooper()));
        message.send();

        try {
            assertTrue(message.blockUntilDelivered(10_000));
        } finally {
            thread.quit();
        }
    }

    /**
     * Until the test ends: what's sent to the player waits behind it
     */
    private void blockPlaybackThread() {
        CountDownLatch blocked = new CountDownLatch(1);
        new Handler(mPlayer.getPlaybackLooper()).post(() -> {
            blocked.countDown();
            try {
                mUnblock.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue("playback thread didn't run", blocked.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    private static long nowMs() {
        return System.nanoTime() / 1_000_000;
    }
}
