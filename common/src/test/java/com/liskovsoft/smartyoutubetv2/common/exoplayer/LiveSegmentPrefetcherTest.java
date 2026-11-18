package com.liskovsoft.smartyoutubetv2.common.exoplayer;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveSegmentPrefetcherTest {
    private static final String AUDIO = "https://rr1---sn-example.googlevideo.com/videoplayback?itag=140&live=1&hang=1";
    private static final String VIDEO = "https://rr1---sn-example.googlevideo.com/videoplayback?itag=299&live=1&hang=1";
    private final AtomicLong mNow = new AtomicLong();
    private final FakeUpstream mUpstream = new FakeUpstream();

    @Before
    public void setUp() {
        LiveSegmentPrefetcher.sClock = mNow::get;
    }

    @After
    public void tearDown() {
        LiveSegmentPrefetcher.sClock = android.os.SystemClock::elapsedRealtime;
    }

    @Test
    public void nextSegmentsAreAskedForAheadAndRead() throws IOException {
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);
        DataSource source = prefetcher.createDataSource();

        assertEquals("segment 10 of itag=140", readSegment(source, AUDIO, 10));
        assertEquals(Arrays.asList("140/10", "140/11", "140/12", "140/13"), mUpstream.requests());

        source.open(new DataSpec(Uri.parse(segmentUrl(AUDIO, 11))));
        // Its response, as the player's listeners see it (see LoadEventInfo)
        assertEquals("100", source.getResponseHeaders().get("X-Head-Seqnum").get(0));
        assertEquals(segmentUrl(AUDIO, 11), String.valueOf(source.getUri()));
        source.close();

        // 11 was read from the one asked for ahead, and the next one is asked for
        assertEquals(Arrays.asList("140/10", "140/11", "140/12", "140/13", "140/14"), mUpstream.requests());
        assertEquals("segment 12 of itag=140", readSegment(source, AUDIO, 12));
    }

    @Test
    public void segmentsPastTheHeadAreNotAskedForAhead() throws IOException {
        mUpstream.head = 11;
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);

        readSegment(prefetcher.createDataSource(), AUDIO, 10);

        // Past the head the server holds the request until the segment exists (hang=1)
        assertEquals(Arrays.asList("140/10", "140/11"), mUpstream.requests());
    }

    @Test
    public void nothingIsAskedForAheadWithoutAKnownHead() throws IOException {
        mUpstream.head = -1;
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);

        readSegment(prefetcher.createDataSource(), AUDIO, 10);

        assertEquals(Collections.singletonList("140/10"), mUpstream.requests());
    }

    @Test
    public void aSegmentThatFailedAheadIsAskedForAgain() throws IOException {
        mUpstream.failOnce.add(11L);
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);
        DataSource source = prefetcher.createDataSource();

        readSegment(source, AUDIO, 10);

        assertEquals("segment 11 of itag=140", readSegment(source, AUDIO, 11));
        assertEquals(2, mUpstream.count("140/11"));
    }

    @Test
    public void aSegmentOfAnotherTrackIsNotRead() throws IOException {
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);

        readSegment(prefetcher.createDataSource(), AUDIO, 10);
        assertEquals("segment 11 of itag=299", readSegment(prefetcher.createDataSource(), VIDEO, 11));

        assertEquals(1, mUpstream.count("299/11"));
    }

    @Test
    public void notASegmentNumberIsPassedThrough() throws IOException {
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);
        DataSource source = prefetcher.createDataSource();

        // The init segment: a byte range, no segment number
        source.open(new DataSpec(Uri.parse(AUDIO), 0, 1000, null));
        source.close();
        // A byte range of a segment (a load resumed part way)
        source.open(new DataSpec(Uri.parse(segmentUrl(AUDIO, 10)), 500, C.LENGTH_UNSET, null));
        source.close();

        assertEquals(Arrays.asList("140/-", "140/10"), mUpstream.requests());
        assertEquals(0, prefetcher.getPendingCount());
    }

    @Test
    public void passedSegmentsAreDropped() throws IOException {
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);
        DataSource source = prefetcher.createDataSource();

        readSegment(source, AUDIO, 10); // 11, 12, 13 ahead
        readSegment(source, AUDIO, 30); // seeked: 31, 32, 33 ahead

        assertEquals(3, prefetcher.getPendingCount());
        assertEquals(3 * "segment 31 of itag=140".length(), prefetcher.getKeptBytes());
    }

    @Test
    public void segmentsNotReadInTimeAreDropped() throws IOException {
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);

        readSegment(prefetcher.createDataSource(), VIDEO, 10); // the quality the player switched from
        mNow.addAndGet(21_000);
        readSegment(prefetcher.createDataSource(), AUDIO, 10);

        assertEquals("only the audio ones", 3, prefetcher.getPendingCount());
        assertEquals(3 * "segment 11 of itag=140".length(), prefetcher.getKeptBytes());
    }

    @Test
    public void thePlayerWaitsForASegmentBeingAskedForAhead() throws Exception {
        CountDownLatch fetchStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        mUpstream.gate = sq -> {
            if (sq == 11) {
                fetchStarted.countDown();
                await(release);
            }
        };
        Executor threads = runnable -> new Thread(runnable).start();
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, threads);
        DataSource source = prefetcher.createDataSource();

        readSegment(source, AUDIO, 10);
        assertTrue(fetchStarted.await(5, TimeUnit.SECONDS));

        AtomicReference<String> read = new AtomicReference<>();
        Thread player = new Thread(() -> {
            try {
                read.set(readSegment(source, AUDIO, 11));
            } catch (IOException e) {
                read.set(e.toString());
            }
        });
        player.start();
        Thread.sleep(200);
        assertTrue("waiting", player.isAlive());

        release.countDown();
        player.join(5_000);

        assertEquals("segment 11 of itag=140", read.get());
        assertEquals(1, mUpstream.count("140/11"));
    }

    @Test
    public void transferListenersHearTheSegmentsAskedForAhead() throws IOException {
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run);
        DataSource source = prefetcher.createDataSource();
        TransferListener listener = new TransferListener() {
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
        };
        source.addTransferListener(listener);

        readSegment(source, AUDIO, 10);

        assertEquals("the player's source and the 3 ahead", 4, mUpstream.listened.size());
        for (List<TransferListener> listeners : mUpstream.listened) {
            assertEquals(Collections.singletonList(listener), listeners);
        }
    }

    @Test
    public void onlyShortSegmentsNeedIt() {
        assertTrue("ultra low latency", LiveSegmentPrefetcher.isNeeded(1_000_000));
        assertTrue("low latency", LiveSegmentPrefetcher.isNeeded(2_000_000));
        assertFalse("normal", LiveSegmentPrefetcher.isNeeded(5_000_000));
        assertFalse("unknown", LiveSegmentPrefetcher.isNeeded(0));
        assertFalse("unknown", LiveSegmentPrefetcher.isNeeded(-1));
    }

    @Test
    public void anEndedStreamsSegmentsAreAskedForAheadWithoutAHead() throws IOException {
        mUpstream.head = -1;
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run, 100);

        readSegment(prefetcher.createDataSource(), AUDIO, 10);

        assertEquals(Arrays.asList("140/10", "140/11", "140/12", "140/13"), mUpstream.requests());
    }

    @Test
    public void anEndedStreamsSegmentsPastItsLastOneAreNotAskedForAhead() throws IOException {
        mUpstream.head = -1;
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run, 11);

        readSegment(prefetcher.createDataSource(), AUDIO, 10);

        assertEquals(Arrays.asList("140/10", "140/11"), mUpstream.requests());
    }

    @Test
    public void theHeadTheServerSaysIsAnEndedStreamsLastSegment() throws IOException {
        mUpstream.head = 11;
        // Its length said one more: rounded up to the second
        LiveSegmentPrefetcher prefetcher = new LiveSegmentPrefetcher(mUpstream, Runnable::run, 12);

        readSegment(prefetcher.createDataSource(), AUDIO, 10);

        assertEquals(Arrays.asList("140/10", "140/11"), mUpstream.requests());
    }

    @Test
    public void anEndedStreamWithShortSegmentsHasOne() {
        // X4GJ6ZKvUrI 6 h after it ended: post-live DVR, its formats' segments of 1 s
        MediaItemFormatInfo formatInfo = endedStream(1, 0);

        assertEquals(1_000_000, LiveSegmentPrefetcher.getEndedStreamSegmentDurationUs(formatInfo));
        assertEquals("sq 0 to 6693", 6693, LiveSegmentPrefetcher.getLastSeqNum(formatInfo, 1_000_000));
        assertNotNull(LiveSegmentPrefetcher.ofEndedStream(formatInfo, mUpstream));
    }

    @Test
    public void anEndedStreamsLastSegmentIsAfterItsStartOne() {
        assertEquals(9092 + 6693, LiveSegmentPrefetcher.getLastSeqNum(endedStream(1, 9092), 1_000_000));
        assertEquals("1673.5 segments: the last one partly filled", 1673, LiveSegmentPrefetcher.getLastSeqNum(endedStream(4, 0), 4_000_000));
    }

    @Test
    public void anEndedStreamOfUnknownLengthHasNoLastSegment() {
        MediaItemFormatInfo formatInfo = proxy(MediaItemFormatInfo.class, map(
                "getAdaptiveFormats", Collections.singletonList(liveFormat("140", 1))));

        assertEquals(-1, LiveSegmentPrefetcher.getLastSeqNum(formatInfo, 1_000_000));
        // Asked for ahead as far as the server says
        assertNotNull(LiveSegmentPrefetcher.ofEndedStream(formatInfo, mUpstream));
    }

    @Test
    public void anEndedStreamWithLongSegmentsHasNone() {
        MediaItemFormatInfo formatInfo = endedStream(5, 0);

        assertEquals(5_000_000, LiveSegmentPrefetcher.getEndedStreamSegmentDurationUs(formatInfo));
        assertNull(LiveSegmentPrefetcher.ofEndedStream(formatInfo, mUpstream));
    }

    @Test
    public void aLiveStreamHasNoneOfAnEndedStream() {
        MediaItemFormatInfo formatInfo = proxy(MediaItemFormatInfo.class, map(
                "isLive", true,
                "getAdaptiveFormats", Collections.singletonList(liveFormat("140", 1)),
                "getSegmentDurationUs", 1_000_000));

        assertEquals(0, LiveSegmentPrefetcher.getEndedStreamSegmentDurationUs(formatInfo));
        assertNull(LiveSegmentPrefetcher.ofEndedStream(formatInfo, mUpstream));
    }

    @Test
    public void aVideoHasNone() {
        // A processed one: its formats are asked for by byte range (indexRange), not by segment number
        MediaFormat format = proxy(MediaFormat.class, map(
                "getITag", "140",
                "getUrl", "https://rr1---sn-example.googlevideo.com/videoplayback?itag=140",
                "getTargetDurationSec", 1));
        MediaItemFormatInfo formatInfo = proxy(MediaItemFormatInfo.class, map(
                "getAdaptiveFormats", Collections.singletonList(format),
                "getMediaDurationMs", 6_694_000L));

        assertEquals(0, LiveSegmentPrefetcher.getEndedStreamSegmentDurationUs(formatInfo));
        assertNull(LiveSegmentPrefetcher.ofEndedStream(formatInfo, mUpstream));
    }

    /**
     * Of 6694 s (lengthSeconds, its formats have no approxDurationMs), not live
     */
    private static MediaItemFormatInfo endedStream(int targetDurationSec, int startSegmentNum) {
        return proxy(MediaItemFormatInfo.class, map(
                "isLiveContent", true,
                "getLengthSeconds", "6694",
                "getMediaDurationMs", 6_694_000L,
                "getAdaptiveFormats", Arrays.asList(liveFormat("248", targetDurationSec), liveFormat("140", targetDurationSec)),
                "getStartSegmentNum", startSegmentNum));
    }

    private static MediaFormat liveFormat(String itag, int targetDurationSec) {
        return proxy(MediaFormat.class, map(
                "getITag", itag,
                "getUrl", "https://rr1---sn-example.googlevideo.com/videoplayback?itag=" + itag + "&source=yt_live_broadcast&live=1",
                "getTargetDurationSec", targetDurationSec));
    }

    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> result = new HashMap<>();

        for (int i = 0; i < keysAndValues.length; i += 2) {
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }

        return result;
    }

    /**
     * Answers the named getters, every other call gets the type's default
     */
    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Map<String, Object> values) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (self, method, args) -> {
            if (values.containsKey(method.getName())) {
                return values.get(method.getName());
            } else if ("hashCode".equals(method.getName())) {
                return System.identityHashCode(self);
            } else if ("equals".equals(method.getName())) {
                return self == args[0];
            } else if ("toString".equals(method.getName())) {
                return type.getSimpleName() + values;
            }

            Class<?> returnType = method.getReturnType();

            if (returnType == boolean.class) {
                return false;
            } else if (returnType == int.class) {
                return 0;
            } else if (returnType == long.class) {
                return 0L;
            } else if (returnType == float.class) {
                return 0f;
            }

            return null;
        });
    }

    private static String readSegment(DataSource source, String trackUrl, long sq) throws IOException {
        try {
            source.open(new DataSpec(Uri.parse(segmentUrl(trackUrl, sq))));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[7]; // smaller than a segment: read in parts
            int read;
            while ((read = source.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        } finally {
            source.close();
        }
    }

    private static String segmentUrl(String trackUrl, long sq) {
        return trackUrl + "&sq=" + sq;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                fail("not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private interface Gate {
        void onOpen(long sq);
    }

    /**
     * A googlevideo live track: a segment's bytes name it, and each response says the head
     */
    private static final class FakeUpstream implements DataSource.Factory {
        private final List<String> mRequests = Collections.synchronizedList(new ArrayList<>());
        final List<Long> failOnce = Collections.synchronizedList(new ArrayList<>());
        final List<List<TransferListener>> listened = Collections.synchronizedList(new ArrayList<>());
        volatile long head = 100;
        volatile Gate gate;

        List<String> requests() {
            synchronized (mRequests) {
                return new ArrayList<>(mRequests);
            }
        }

        int count(String request) {
            return Collections.frequency(requests(), request);
        }

        @Override
        public DataSource createDataSource() {
            return new FakeSource(this);
        }
    }

    private static final class FakeSource implements DataSource {
        private final FakeUpstream mUpstream;
        private final List<TransferListener> mListeners = new ArrayList<>();
        private byte[] mData;
        private int mPosition;
        private Uri mUri;

        FakeSource(FakeUpstream upstream) {
            mUpstream = upstream;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            mListeners.add(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            String sq = dataSpec.uri.getQueryParameter("sq");
            String itag = dataSpec.uri.getQueryParameter("itag");
            mUpstream.mRequests.add(itag + "/" + (sq != null ? sq : "-"));
            if (sq != null) {
                mUpstream.listened.add(new ArrayList<>(mListeners));
            }
            Gate gate = mUpstream.gate;
            if (gate != null && sq != null) {
                gate.onOpen(Long.parseLong(sq));
            }
            if (sq != null && mUpstream.failOnce.remove(Long.valueOf(sq))) {
                throw new IOException("failed once: " + sq);
            }
            mUri = dataSpec.uri;
            mData = ("segment " + (sq != null ? sq : "init") + " of itag=" + itag).getBytes("UTF-8");
            mPosition = (int) dataSpec.position;
            return mData.length - mPosition;
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) {
            if (mPosition >= mData.length) {
                return C.RESULT_END_OF_INPUT;
            }
            int count = Math.min(readLength, mData.length - mPosition);
            System.arraycopy(mData, mPosition, buffer, offset, count);
            mPosition += count;
            return count;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return mUri;
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            Map<String, List<String>> headers = new HashMap<>();
            if (mUpstream.head >= 0) {
                headers.put("X-Head-Seqnum", Collections.singletonList(String.valueOf(mUpstream.head)));
            }
            return headers;
        }

        @Override
        public void close() {
            mData = null;
        }
    }
}
