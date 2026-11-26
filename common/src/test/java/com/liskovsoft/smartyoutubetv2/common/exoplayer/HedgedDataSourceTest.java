package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.TransferListener;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A DASH init chunk that doesn't answer soon is asked for again on another connection, the first answer read (F0SVV9N8: 4 s waits
 * for a 3 KB init chunk the retry got in 95-298 ms). See HedgedDataSource.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HedgedDataSourceTest {
    private static final long HEDGE_MS = 50;
    private static final DataSpec INIT = new DataSpec(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback"), 0, 3_000, null,
            DataSpec.FLAG_INITIALIZATION);
    private static final DataSpec MEDIA = new DataSpec(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback"), 3_000, 500_000,
            null);
    private final List<FakeSource> mMade = new ArrayList<>();

    @Test
    public void aMediaChunkIsAskedForOnce() throws Exception {
        FakeSource first = willAnswerAfter(500, 500_000);
        DataSource source = HedgedDataSource.create(this::next, HEDGE_MS);

        assertEquals(500_000, source.open(MEDIA));

        assertEquals(1, mMade.size());
    }

    @Test
    public void anInitChunkAnsweredSoonIsAskedForOnce() throws Exception {
        willAnswerAfter(0, 3_000);
        DataSource source = HedgedDataSource.create(this::next, HEDGE_MS);

        assertEquals(3_000, source.open(INIT));

        assertEquals(1, mMade.size());
    }

    @Test
    public void anInitChunkThatHangsIsAskedForAgainAndTheFirstAnswerIsRead() throws Exception {
        FakeSource hung = willAnswerAfter(5_000, 3_000);
        FakeSource again = willAnswerAfter(0, 3_000);
        DataSource source = HedgedDataSource.create(this::next, HEDGE_MS);

        long startMs = System.currentTimeMillis();
        source.open(INIT);
        assertTrue(System.currentTimeMillis() - startMs < 2_000);

        byte[] buffer = new byte[16];
        source.read(buffer, 0, buffer.length);
        assertEquals(1, again.reads);
        assertEquals(0, hung.reads);

        source.close();
        assertTrue(again.isClosed);
    }

    /**
     * The first, still opening, closes itself when it ends; the next request goes to another source than it
     */
    @Test
    public void theFirstStillOpeningIsClosedWhenItEndsAndNotUsedAgain() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        FakeSource hung = willAnswerOn(release, 3_000);
        willAnswerAfter(0, 3_000);
        FakeSource next = willAnswerAfter(0, 500_000);
        DataSource source = HedgedDataSource.create(this::next, HEDGE_MS);

        source.open(INIT);
        source.close();
        source.open(MEDIA);

        assertEquals(1, next.opens);

        release.countDown();
        assertTrue(hung.closed.await(2, TimeUnit.SECONDS));
    }

    /**
     * An answer that's an error (e.g. a 403) comes back at once: not asked again
     */
    @Test
    public void aFastFailureIsntAskedForAgain() throws Exception {
        willFailAfter(0);
        DataSource source = HedgedDataSource.create(this::next, HEDGE_MS);

        try {
            source.open(INIT);
            fail();
        } catch (InvalidResponseCodeException e) {
            assertEquals(403, e.responseCode);
        }

        assertEquals(1, mMade.size());
    }

    @Test
    public void bothFailingGivesTheFirstsError() throws Exception {
        FakeSource first = willFailAfter(200);
        willFailAfter(0);
        DataSource source = HedgedDataSource.create(this::next, HEDGE_MS);

        try {
            source.open(INIT);
            fail();
        } catch (InvalidResponseCodeException e) {
            assertSame(first.error, e);
        }
    }

    /**
     * The one asked again isn't counted by the listeners (e.g. the bandwidth meter): the first request's are
     */
    @Test
    public void theListenersAreTheFirstRequestsOnly() throws Exception {
        FakeSource hung = willAnswerAfter(5_000, 3_000);
        FakeSource again = willAnswerAfter(0, 3_000);
        DataSource source = HedgedDataSource.create(this::next, HEDGE_MS);
        source.addTransferListener(new TransferListener() {
            @Override
            public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
            }

            @Override
            public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
            }

            @Override
            public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
            }

            @Override
            public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
            }
        });

        source.open(INIT);

        assertEquals(1, hung.listeners);
        assertEquals(0, again.listeners);
    }

    private final List<FakeSource> mPlanned = new ArrayList<>();

    private DataSource next() {
        FakeSource source = mPlanned.isEmpty() ? new FakeSource(0, 0, null, null) : mPlanned.remove(0);
        mMade.add(source);

        return source;
    }

    private FakeSource willAnswerAfter(long delayMs, long length) {
        FakeSource source = new FakeSource(delayMs, length, null, null);
        mPlanned.add(source);

        return source;
    }

    private FakeSource willAnswerOn(CountDownLatch release, long length) {
        FakeSource source = new FakeSource(0, length, null, release);
        mPlanned.add(source);

        return source;
    }

    private FakeSource willFailAfter(long delayMs) {
        FakeSource source = new FakeSource(delayMs, 0, new InvalidResponseCodeException(403, null, Collections.emptyMap(), INIT),
                null);
        mPlanned.add(source);

        return source;
    }

    private static final class FakeSource implements DataSource {
        final long delayMs;
        final long length;
        @Nullable
        final IOException error;
        @Nullable
        final CountDownLatch release;
        final CountDownLatch closed = new CountDownLatch(1);
        volatile int opens;
        volatile int reads;
        volatile int listeners;
        volatile boolean isClosed;

        FakeSource(long delayMs, long length, @Nullable IOException error, @Nullable CountDownLatch release) {
            this.delayMs = delayMs;
            this.length = length;
            this.error = error;
            this.release = release;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            listeners++;
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            opens++;

            try {
                if (release != null) {
                    release.await();
                } else {
                    Thread.sleep(delayMs);
                }
            } catch (InterruptedException e) {
                throw new IOException(e);
            }

            if (error != null) {
                throw error;
            }

            return length;
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) {
            reads++;
            return readLength;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return INIT.uri;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return Collections.emptyMap();
        }

        @Override
        public void close() {
            isClosed = true;
            closed.countDown();
        }
    }
}
