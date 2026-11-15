package com.google.android.exoplayer2.source.sabr;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Asks for the next segments of a live stream with short segments (ultra low latency: 1 s) while the player loads one, as
 * LiveSegmentPrefetcher does for DASH. An edge answers a SABR request for a segment it hasn't cached in about a second however old
 * the segment is, and the player asks for one segment of a track at a time: 1 s segments came at 0.8x of real time, and the stream
 * stalled every 25 s (seen 2026-11-15). Requests in parallel are answered in the same time each.<br/>
 * A response fetched ahead is the one the player's own request for that segment gets: it's read from memory then, not asked again.
 * Only segments the server said exist (see LiveMetadata) are asked for ahead: a later one is held until it exists.
 */
final class SabrLivePrefetcher {
    private static final String TAG = SabrLivePrefetcher.class.getSimpleName();
    /**
     * Asked for past the one the player loads: with it, 1 + 3 requests of about a second each are going on for 1 s segments
     */
    static final int PREFETCH_COUNT = 3;
    /**
     * Prefetched for segments shorter than this: a 5 s segment is loaded faster than it plays
     */
    static final int MAX_SEGMENT_DURATION_SEC = 2;
    /**
     * Of a response asked for ahead that holds one segment, in segments of the format's bitrate (one more for each further segment it
     * holds): a bigger one is the server's burst toward its readahead (15 s, seen at the start of a stream on 2026-11-15: over 8 MB for
     * a 2 s segment of 1440p), not a segment
     */
    private static final float MAX_RESPONSE_SEGMENTS = 2.5f;
    /**
     * Of the segments a response is taken to hold (see DefaultSabrChunkSource#maybePrefetch): 3 behind the head of a 1 s stream
     * (2026-11-15). More is a burst toward the readahead, which MAX_RESPONSE_SEGMENTS still tells.
     */
    static final int MAX_SEGMENTS_PER_RESPONSE = 4;
    private static final int MIN_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 12 * 1024 * 1024;
    /**
     * After a burst, nothing is asked for ahead for this long: the server sends plenty, and the requests ahead would each be answered
     * with the same burst
     */
    private static final long BURST_PAUSE_MS = 10_000;
    /**
     * The player waits for a response asked for ahead at most this long, then asks itself
     */
    private static final long WAIT_MS = 10_000;
    private static final int READ_BUFFER_BYTES = 32 * 1024;
    private static final Executor sExecutor = createExecutor();
    private final DataSource.Factory dataSourceFactory;
    @Nullable private final TransferListener transferListener;
    /**
     * Of the format asked for ahead, by the sequence number of the segment each response is for
     */
    private final Map<Long, Fetch> fetches = new TreeMap<>();
    private int itag = -1;
    /**
     * Nothing is asked for ahead before (elapsed realtime), see BURST_PAUSE_MS
     */
    private volatile long pausedUntilMs;

    SabrLivePrefetcher(DataSource.Factory dataSourceFactory, @Nullable TransferListener transferListener) {
        this.dataSourceFactory = dataSourceFactory;
        this.transferListener = transferListener;
    }

    /**
     * The player's request for a segment: served by the response fetched ahead for it, if any. The ones for earlier segments won't be.
     *
     * @param upstream asked when there's none, or when it failed
     */
    DataSource dataSourceFor(int itag, long sequenceNumber, DataSource upstream) {
        if (itag != this.itag) {
            return upstream;
        }

        Fetch fetch = null;

        for (Iterator<Map.Entry<Long, Fetch>> it = fetches.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Long, Fetch> entry = it.next();

            if (entry.getKey() > sequenceNumber) {
                break;
            }

            if (entry.getKey() == sequenceNumber) {
                fetch = entry.getValue();
            }

            it.remove();
        }

        return fetch != null ? new PrefetchedDataSource(fetch, upstream) : upstream;
    }

    /**
     * Asks for the segment ahead, unless it was already
     *
     * @param dataSpec the request for it (see SabrManifest#createLiveRequestAfter)
     */
    void prefetch(int itag, long sequenceNumber, DataSpec dataSpec, int maxBytes) {
        if (itag != this.itag) {
            clear();
            this.itag = itag;
        }

        if (fetches.containsKey(sequenceNumber) || SystemClock.elapsedRealtime() < pausedUntilMs) {
            return;
        }

        DataSource dataSource = dataSourceFactory.createDataSource();

        if (transferListener != null) {
            dataSource.addTransferListener(transferListener); // what's fetched counts in the bandwidth estimate
        }

        Fetch fetch = new Fetch(sequenceNumber);
        fetches.put(sequenceNumber, fetch);
        sExecutor.execute(() -> fetch.load(dataSource, dataSpec, maxBytes,
                () -> pausedUntilMs = SystemClock.elapsedRealtime() + BURST_PAUSE_MS));
    }

    /**
     * Of a response asked for ahead: about its segments of the format, a few more at most (see MAX_RESPONSE_SEGMENTS)
     *
     * @param bitrate the format's, in bits per second
     * @param segments how many a response holds (see MAX_SEGMENTS_PER_RESPONSE)
     */
    static int getMaxResponseBytes(int bitrate, long segmentDurationMs, int segments) {
        long segmentBytes = bitrate > 0 ? bitrate / 8L * segmentDurationMs / 1_000 : 0;
        long maxBytes = (long) (segmentBytes * (segments - 1 + MAX_RESPONSE_SEGMENTS));

        return (int) Math.max(MIN_RESPONSE_BYTES, Math.min(MAX_RESPONSE_BYTES, maxBytes));
    }

    /**
     * A seek or another format: what's asked for ahead won't be read
     */
    void clear() {
        fetches.clear();
        itag = -1;
    }

    private static Executor createExecutor() {
        AtomicInteger count = new AtomicInteger();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                PREFETCH_COUNT * 2, PREFETCH_COUNT * 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "SabrLivePrefetcher-" + count.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * A response asked for ahead
     */
    @VisibleForTesting
    static final class Fetch {
        final long sequenceNumber;
        private final CountDownLatch done = new CountDownLatch(1);
        @Nullable private volatile byte[] data;
        @Nullable private volatile IOException error;

        Fetch(long sequenceNumber) {
            this.sequenceNumber = sequenceNumber;
        }

        /**
         * @param onBurst run when the response is a burst, bigger than the given size (see MAX_RESPONSE_SEGMENTS), before it's done
         */
        void load(DataSource dataSource, DataSpec dataSpec, int maxBytes, Runnable onBurst) {
            long startMs = SystemClock.elapsedRealtime();

            try {
                dataSource.open(dataSpec);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[READ_BUFFER_BYTES];
                int read;

                while ((read = dataSource.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
                    out.write(buffer, 0, read);

                    if (out.size() > maxBytes) {
                        onBurst.run();
                        throw new IOException("A burst: over " + maxBytes + " bytes");
                    }
                }

                data = out.toByteArray();
                Log.d(TAG, "Segment " + sequenceNumber + ": " + out.size() + " bytes in "
                        + (SystemClock.elapsedRealtime() - startMs) + " ms");
            } catch (IOException | RuntimeException e) {
                error = e instanceof IOException ? (IOException) e : new IOException(e);
                Log.w(TAG, "Segment " + sequenceNumber + " failed: " + e.getMessage());
            } finally {
                try {
                    dataSource.close();
                } catch (IOException ignored) {
                    // read already
                }

                done.countDown();
            }
        }

        /**
         * @return the response, null when it failed or didn't come in time
         */
        @Nullable
        byte[] await(long timeoutMs) throws InterruptedIOException {
            try {
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    return null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Canceled while waiting for a segment asked for ahead");
            }

            return error == null ? data : null;
        }

        @VisibleForTesting
        void complete(@Nullable byte[] data, @Nullable IOException error) {
            this.data = data;
            this.error = error;
            done.countDown();
        }
    }

    /**
     * Reads the response asked for ahead, else (it failed, or didn't come in time) asks upstream
     */
    @VisibleForTesting
    static final class PrefetchedDataSource implements DataSource {
        private final Fetch fetch;
        private final DataSource upstream;
        @Nullable private byte[] data;
        private int position;
        private boolean isUpstream;
        @Nullable private Uri uri;

        PrefetchedDataSource(Fetch fetch, DataSource upstream) {
            this.fetch = fetch;
            this.upstream = upstream;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            data = fetch.await(WAIT_MS);
            uri = dataSpec.uri;

            if (data == null) {
                Log.w(TAG, "Segment " + fetch.sequenceNumber + " asked for ahead didn't come: asking again");
                isUpstream = true;
                return upstream.open(dataSpec);
            }

            // A load retried after an error resumes where it stopped (see ContainerMediaChunk#load)
            position = (int) Math.min(Math.max(dataSpec.position, 0), data.length);
            return data.length - position;
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            if (isUpstream) {
                return upstream.read(buffer, offset, readLength);
            }

            if (data == null || position >= data.length) {
                return C.RESULT_END_OF_INPUT;
            }

            int length = Math.min(readLength, data.length - position);
            System.arraycopy(data, position, buffer, offset, length);
            position += length;
            return length;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return isUpstream ? upstream.getUri() : uri;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return isUpstream ? upstream.getResponseHeaders() : Collections.emptyMap();
        }

        @Override
        public void close() throws IOException {
            if (isUpstream) {
                isUpstream = false;
                upstream.close();
            }

            data = null;
        }
    }
}
