package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A DASH init chunk that hasn't answered in a while is asked for again on another connection, and the first answer is read
 * (F0SVV9N8: two DASH starts waited 4 s, the read timeout, for a 3 KB init chunk the retry got in 95-298 ms). The first request isn't
 * cancelled: its answer may still come first, and the later one is closed. Only the requests flagged
 * {@link DataSpec#FLAG_INITIALIZATION}: small GETs a start waits for. Not SABR's: its requests carry the stream's state, and one sent
 * twice may put the stream out of step.
 */
final class HedgedDataSource implements DataSource {
    private static final String TAG = HedgedDataSource.class.getSimpleName();
    /**
     * The init chunks of the starts that answered took 60-450 ms, the slow server's 3 s (F0SVV9N8)
     */
    static final long HEDGE_AFTER_MS = 1_500;
    private static final ExecutorService sExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "HedgedOpen");
        thread.setDaemon(true);
        return thread;
    });
    private final DataSource.Factory mFactory;
    private final long mHedgeAfterMs;
    private final List<TransferListener> mListeners = new ArrayList<>();
    /**
     * Opened first, for every request: a source keeps the state of its host (see GoogleVideoFallbackDataSource)
     */
    private DataSource mPrimary;
    /**
     * The one read and closed, null when none is open
     */
    @Nullable
    private DataSource mOpened;

    private HedgedDataSource(DataSource.Factory factory, long hedgeAfterMs) {
        mFactory = factory;
        mHedgeAfterMs = hedgeAfterMs;
        mPrimary = factory.createDataSource();
    }

    static DataSource.Factory wrap(DataSource.Factory factory) {
        return () -> new HedgedDataSource(factory, HEDGE_AFTER_MS);
    }

    @VisibleForTesting
    static DataSource create(DataSource.Factory factory, long hedgeAfterMs) {
        return new HedgedDataSource(factory, hedgeAfterMs);
    }

    /**
     * Of the first request only: the one asked for again isn't counted twice (e.g. by the bandwidth meter)
     */
    @Override
    public void addTransferListener(TransferListener transferListener) {
        mListeners.add(transferListener);
        mPrimary.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        if (!isHedged(dataSpec)) {
            mOpened = mPrimary;
            return mPrimary.open(dataSpec);
        }

        BlockingQueue<Attempt> finished = new LinkedBlockingQueue<>();
        Attempt first = Attempt.start(mPrimary, dataSpec, finished);
        Attempt second = null;
        IOException firstError = null;

        try {
            Attempt done = finished.poll(mHedgeAfterMs, TimeUnit.MILLISECONDS);

            if (done == null) {
                Log.d(TAG, "No answer in %s ms: asking again on another connection", mHedgeAfterMs);
                second = Attempt.start(mFactory.createDataSource(), dataSpec, finished);
            }

            int pending = second != null ? 2 : 1;

            while (pending > 0) {
                done = done != null ? done : finished.take();
                pending--;

                if (done.error == null) {
                    return onAnswer(done, first, second);
                }

                if (done == first) {
                    firstError = done.error;
                }

                // A fast failure is an answer (e.g. a 403): not asked again
                if (second == null) {
                    break;
                }

                done = null;
            }
        } catch (InterruptedException e) {
            // The load was cancelled: they close when they end
            first.abandon();

            if (second != null) {
                second.abandon();
            }

            replacePrimaryIfOpening(first);
            mOpened = null;
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
        }

        // Both failed: the first's error, its source closed by the caller (see DataSource#close)
        if (second != null) {
            second.abandon();
        }

        mOpened = mPrimary;
        throw firstError != null ? firstError : second.error;
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (mOpened == null) {
            throw new IOException("Not opened");
        }

        return mOpened.read(buffer, offset, readLength);
    }

    @Nullable
    @Override
    public Uri getUri() {
        return mOpened != null ? mOpened.getUri() : null;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return mOpened != null ? mOpened.getResponseHeaders() : mPrimary.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        DataSource opened = mOpened;
        mOpened = null;

        if (opened != null) {
            opened.close();
        }
    }

    private long onAnswer(Attempt winner, Attempt first, @Nullable Attempt second) {
        Attempt loser = winner == first ? second : first;

        if (loser != null) {
            loser.abandon();
        }

        if (winner != first) {
            Log.d(TAG, "The request asked again answered first");
            replacePrimaryIfOpening(first);
        }

        mOpened = winner.source;

        return winner.length;
    }

    /**
     * The first request still opening on its thread: the next requests go to another source than its own, which isn't safe to use from
     * two threads (it closes itself when it ends, see Attempt#abandon)
     */
    private void replacePrimaryIfOpening(Attempt first) {
        if (first.isEnded()) {
            return;
        }

        mPrimary = mFactory.createDataSource();

        for (TransferListener listener : mListeners) {
            mPrimary.addTransferListener(listener);
        }
    }

    private static boolean isHedged(DataSpec dataSpec) {
        return dataSpec.isFlagSet(DataSpec.FLAG_INITIALIZATION) && dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET;
    }

    /**
     * A source's open on a thread of its own: told to the queue when it ends
     */
    private static final class Attempt {
        final DataSource source;
        long length;
        @Nullable
        IOException error;
        private boolean mIsEnded;
        private boolean mIsAbandoned;

        private Attempt(DataSource source) {
            this.source = source;
        }

        static Attempt start(DataSource source, DataSpec dataSpec, BlockingQueue<Attempt> finished) {
            Attempt attempt = new Attempt(source);
            sExecutor.execute(() -> attempt.run(dataSpec, finished));

            return attempt;
        }

        private void run(DataSpec dataSpec, BlockingQueue<Attempt> finished) {
            try {
                length = source.open(dataSpec);
            } catch (IOException e) {
                error = e;
            } catch (RuntimeException e) {
                error = new IOException(e);
            }

            synchronized (this) {
                mIsEnded = true;

                if (mIsAbandoned) {
                    closeQuietly();
                }
            }

            finished.add(this);
        }

        synchronized boolean isEnded() {
            return mIsEnded;
        }

        /**
         * Not read: closed now if it ended, else when it does
         */
        synchronized void abandon() {
            mIsAbandoned = true;

            if (mIsEnded) {
                closeQuietly();
            }
        }

        private void closeQuietly() {
            try {
                source.close();
            } catch (IOException | RuntimeException e) {
                // Not read, nothing to keep
            }
        }
    }
}
