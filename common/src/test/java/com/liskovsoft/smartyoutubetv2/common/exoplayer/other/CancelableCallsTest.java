package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.upstream.Loader;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Request;

/**
 * A seek cancels the loads going on: a load blocked in an OkHttp socket read isn't woken by its thread's interrupt, only by its call's
 * cancel(). Before, it waited until the server answered or the read timed out (1.6-5 s for a rewind at a live stream's head).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CancelableCallsTest {
    private static final Request REQUEST = new Request.Builder().url("https://rr1---sn-test.googlevideo.com/videoplayback").build();

    @After
    public void tearDown() {
        Loader.setCancelListener(null);
    }

    @Test
    public void aCanceledLoadCancelsItsCall() throws Exception {
        CancelableCalls.install();
        FakeCalls calls = new FakeCalls();
        BlockedLoad load = new BlockedLoad(new CancelableCalls(calls));
        Loader loader = new Loader("test");
        loader.startLoading(load, new NoCallback(), 0);
        assertTrue(load.inCall.await(2, TimeUnit.SECONDS));

        loader.cancelLoading();

        assertTrue("the load ended", load.ended.await(1, TimeUnit.SECONDS));
        assertEquals(1, calls.canceled.get());
        loader.release();
    }

    @Test
    public void aCallMadeOffALoadersThreadIsntCanceledWithALoad() {
        FakeCalls calls = new FakeCalls();

        new CancelableCalls(calls).newCall(REQUEST);
        CancelableCalls.onLoadCanceled(Thread.currentThread());

        assertNull(CancelableCalls.getCall(Thread.currentThread()));
        assertEquals(0, calls.canceled.get());
    }

    /**
     * Its call blocks as a socket read does: an interrupt doesn't end it, its call's cancel() does (or it gives up after 5 s)
     */
    private static final class BlockedLoad implements Loader.Loadable {
        private final Call.Factory factory;
        final CountDownLatch inCall = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);

        BlockedLoad(Call.Factory factory) {
            this.factory = factory;
        }

        @Override
        public void cancelLoad() {
        }

        @Override
        public void load() throws IOException {
            try {
                FakeCall call = (FakeCall) Proxy.getInvocationHandler(factory.newCall(REQUEST));
                inCall.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

                while (call.canceled.getCount() > 0 && System.nanoTime() < deadline) {
                    try {
                        call.canceled.await(10, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        // a socket read ignores it
                    }
                }

                throw new IOException(call.canceled.getCount() == 0 ? "Canceled" : "timeout");
            } finally {
                ended.countDown();
            }
        }
    }

    private static final class FakeCalls implements Call.Factory {
        final AtomicInteger canceled = new AtomicInteger();

        @NonNull
        @Override
        public Call newCall(@NonNull Request request) {
            FakeCall handler = new FakeCall(this);
            return (Call) Proxy.newProxyInstance(Call.class.getClassLoader(), new Class<?>[] {Call.class}, handler);
        }
    }

    private static final class FakeCall implements java.lang.reflect.InvocationHandler {
        private final FakeCalls calls;
        final CountDownLatch canceled = new CountDownLatch(1);

        FakeCall(FakeCalls calls) {
            this.calls = calls;
        }

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            switch (method.getName()) {
                case "cancel":
                    calls.canceled.incrementAndGet();
                    canceled.countDown();
                    return null;
                case "isCanceled":
                    return canceled.getCount() == 0;
                case "request":
                    return REQUEST;
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                default:
                    return null;
            }
        }
    }

    private static final class NoCallback implements Loader.Callback<BlockedLoad> {
        @Override
        public void onLoadCompleted(BlockedLoad loadable, long elapsedRealtimeMs, long loadDurationMs) {
        }

        @Override
        public void onLoadCanceled(BlockedLoad loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {
        }

        @Override
        public Loader.LoadErrorAction onLoadError(BlockedLoad loadable, long elapsedRealtimeMs, long loadDurationMs,
                                                  IOException error, int errorCount) {
            return Loader.DONT_RETRY;
        }
    }
}
