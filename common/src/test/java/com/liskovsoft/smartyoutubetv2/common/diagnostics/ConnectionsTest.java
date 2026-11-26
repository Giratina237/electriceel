package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ext.cronet.CronetDataSource;
import com.google.android.exoplayer2.source.chunk.Chunk;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.upstream.TransferListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;

import org.chromium.net.CronetException;
import org.chromium.net.RequestFinishedInfo;
import org.chromium.net.UrlRequest;
import org.chromium.net.UrlResponseInfo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ConnectionsTest {
    private static final String HOST = "rr1---sn-example.googlevideo.com";
    private static final InetAddress IPV4 = address(new byte[] {(byte) 142, (byte) 250, 1, 1});
    private static final InetAddress IPV6 = address(new byte[] {0x26, 0x07, (byte) 0xf8, (byte) 0xb0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
    private static final Format VIDEO = Format.createContainerFormat("248", "video/webm", "video/x-vnd.on2.vp9", "vp9", 17_668_000,
            0, null);
    private static final Format AUDIO = Format.createContainerFormat("251", "audio/webm", "audio/opus", "opus", 128_000, 0, "en");
    /**
     * Of a SABR request: each has its own (rn)
     */
    private static final String URL = "https://" + HOST + "/videoplayback?sabr=1&rn=3";
    /**
     * The wall clock at the test clock's 0
     */
    private static final long WALL_T0 = 1_792_000_000_000L;
    private final AtomicLong mNow = new AtomicLong();
    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();
    /**
     * Not run: the listener is told what OkHttp would tell it
     */
    private final Call mCall = new OkHttpClient().newCall(new Request.Builder().url("https://example.com/videoplayback").build());

    @Before
    public void setUp() {
        Connections.resetForTesting();
        Connections.sClock = mNow::get;
    }

    @After
    public void tearDown() {
        Connections.resetForTesting();
        Connections.sClock = android.os.SystemClock::elapsedRealtime;
    }

    /**
     * A segment of 1 KB that took 3.5 s on a connection ready at once: its request waited for the server, not for the bytes
     */
    @Test
    public void aResponseIsTheWaitForItsFirstByte() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.requestHeadersStart(mCall);
        mNow.set(4_500);
        listener.responseBodyStart(mCall);
        mNow.set(4_510);
        listener.responseBodyStart(mCall); // told once by OkHttp; a second one changes nothing
        listener.callEnd(mCall);

        List<VideoStart.Stage> stages = Connections.take(500, 5_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.STAGE_RESPONSE, stages.get(0).name);
        assertNull(stages.get(0).detail);
        assertEquals(1, stages.get(0).depth);
        assertEquals(1_000, stages.get(0).startMs);
        assertEquals(4_500, stages.get(0).endMs);
    }

    /**
     * The start is looked at or given up while a request waits for its answer: what it waits for
     */
    @Test
    public void oneWaitingIsStillGoing() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.requestHeadersStart(mCall);

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.DETAIL_GOING, stages.get(0).detail);
        assertEquals(1_000, stages.get(0).startMs);
        assertEquals(3_000, stages.get(0).endMs);
    }

    /**
     * Followed up (e.g. a redirect), the response before ends: its body isn't read
     */
    @Test
    public void aFollowUpEndsTheResponseBefore() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.requestHeadersStart(mCall);
        mNow.set(1_200);
        listener.requestHeadersStart(mCall);
        mNow.set(1_500);
        listener.responseBodyStart(mCall);

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals(2, stages.size());
        assertEquals(1_000, stages.get(0).startMs);
        assertEquals(1_200, stages.get(0).endMs);
        assertEquals(1_200, stages.get(1).startMs);
        assertEquals(1_500, stages.get(1).endMs);
    }

    /**
     * It says it failed: a slow answer and none look the same otherwise
     */
    @Test
    public void aFailedCallEndsItsResponse() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.requestHeadersStart(mCall);
        mNow.set(2_000);
        listener.callFailed(mCall, new IOException("timeout"));

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals(1, stages.size());
        assertEquals("failed", stages.get(0).detail);
        assertEquals(2_000, stages.get(0).endMs);
    }

    /**
     * Its headers timed out (S4PGCB6X: a video chunk's "response 4002" twice, OkHttp's read timeout)
     */
    @Test
    public void aResponseThatTimedOutSaysSo() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.requestHeadersStart(mCall);
        mNow.set(5_002);
        listener.responseFailed(mCall, new SocketTimeoutException("timeout"));

        List<VideoStart.Stage> stages = Connections.take(500, 6_000);

        assertEquals(1, stages.size());
        assertEquals("timed out", stages.get(0).detail);
        assertEquals(5_002, stages.get(0).endMs);
    }

    /**
     * A failure shorter than a slow answer is there too: one that fails at once (e.g. the connection reset) is what the player waited
     * on after it
     */
    @Test
    public void aShortFailureIsThere() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.requestHeadersStart(mCall);
        mNow.set(1_020);
        listener.responseFailed(mCall, new SocketException("Connection reset"));

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals(1, stages.size());
        assertEquals("failed", stages.get(0).detail);
    }

    /**
     * One cancelled (e.g. a seek's) isn't a failure: short, it's left out as before
     */
    @Test
    public void aShortCancelIsLeftOut() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.requestHeadersStart(mCall);
        mNow.set(1_020);
        listener.callFailed(mCall, new IOException("Canceled"));

        assertTrue(Connections.take(500, 3_000).isEmpty());
    }

    /**
     * Sent before the start, a request is of the source before (e.g. the video before's), even one still waiting
     */
    @Test
    public void responsesOfTheSourceBeforeAreLeftOut() {
        Connections.addResponse(100, 900, null);
        Connections.addResponse(400, -1, null);
        Connections.addResponse(600, 900, null);

        List<VideoStart.Stage> stages = Connections.take(500, 2_000);

        assertEquals(1, stages.size());
        assertEquals(600, stages.get(0).startMs);
        assertEquals(900, stages.get(0).endMs);
    }

    /**
     * The default engine's init chunks took 7.9 s: with no OkHttp client, a request is timed whole, its open, from being made to its
     * headers
     */
    @Test
    public void anOpeningIsTimedFromMadeToHeaders() throws IOException {
        DataSource source = Connections.timeOpenings(new FakeDataSource(7_900, null));
        mNow.set(1_000);
        source.open(new DataSpec(Uri.parse("https://example.com/videoplayback")));
        mNow.set(9_500);
        source.close(); // its end isn't the opening's

        List<VideoStart.Stage> stages = Connections.take(500, 10_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.STAGE_OPENING, stages.get(0).name);
        assertNull(stages.get(0).detail);
        assertEquals(1_000, stages.get(0).startMs);
        assertEquals(8_900, stages.get(0).endMs);
    }

    /**
     * One that fails to open (e.g. a connect timeout) ends then, not still going
     */
    @Test
    public void aFailedOpeningEndsWhenItFails() {
        DataSource source = Connections.timeOpenings(new FakeDataSource(5_000, new IOException("connect timed out")));
        mNow.set(1_000);

        try {
            source.open(new DataSpec(Uri.parse("https://example.com/videoplayback")));
        } catch (IOException e) {
            // As the player gets it
        }

        List<VideoStart.Stage> stages = Connections.take(500, 7_000);

        assertEquals(1, stages.size());
        assertEquals("failed", stages.get(0).detail);
        assertEquals(1_000, stages.get(0).startMs);
        assertEquals(6_000, stages.get(0).endMs);
    }

    /**
     * Cronet wraps its connect timeout (S4PGCB6X #4: an OpenException of a SocketTimeoutException)
     */
    @Test
    public void aWrappedTimeoutOfAnOpeningSaysSo() {
        DataSource source = Connections.timeOpenings(new FakeDataSource(8_000, new IOException(new SocketTimeoutException())));
        mNow.set(1_000);

        try {
            source.open(new DataSpec(Uri.parse("https://example.com/videoplayback")));
        } catch (IOException e) {
            // As the player gets it
        }

        assertEquals("timed out", Connections.take(500, 10_000).get(0).detail);
    }

    /**
     * EY23XNK9 19:07:40, WVE7ZKDD 00:30:36: the openings a restore seek cancelled said "failed". Cronet's interrupted open is its own
     * InterruptedIOException, not java.io's: cancelled, left out when short, as the other engines' are
     */
    @Test
    public void aCronetOpeningCancelledIsntAFailure() {
        DataSource source = Connections.timeOpenings(new FakeDataSource(40, cronetInterrupted()));
        mNow.set(1_000);

        try {
            source.open(new DataSpec(Uri.parse("https://example.com/videoplayback")));
        } catch (IOException e) {
            // As the player gets it
        }

        assertTrue(Connections.take(500, 3_000).isEmpty());
    }

    /**
     * Cancelled after it waited long: shown, as cancelled
     */
    @Test
    public void aLongCronetOpeningCancelledSaysSo() {
        DataSource source = Connections.timeOpenings(new FakeDataSource(900, cronetInterrupted()));
        mNow.set(1_000);

        try {
            source.open(new DataSpec(Uri.parse("https://example.com/videoplayback")));
        } catch (IOException e) {
            // As the player gets it
        }

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals(1, stages.size());
        assertEquals(Connections.FAILURE_CANCELLED, stages.get(0).detail);
        assertEquals(1_900, stages.get(0).endMs);
    }

    /**
     * As CronetDataSource#open throws it when its thread is interrupted (e.g. a seek cancelled the load)
     */
    private static IOException cronetInterrupted() {
        return new CronetDataSource.OpenException(new CronetDataSource.InterruptedIOException(new InterruptedException()),
                new DataSpec(Uri.parse("https://example.com/videoplayback")), UrlRequest.Status.INVALID);
    }

    /**
     * WVE7ZKDD 11-24 01:57:39: both init chunks' openings took 5.08 s, and nothing told the lookup from the connect or the server.
     * Cronet's metrics of the request, once it ended, say where the time went.
     */
    @Test
    public void aCronetOpeningSaysWhereItsTimeWent() throws IOException {
        Connections.sWallClock = () -> WALL_T0 + mNow.get();
        DataSource source = Connections.timeOpenings(new FakeDataSource(5_080, null));
        mNow.set(1_000);
        source.open(new DataSpec(Uri.parse(URL)));
        mNow.set(6_500);
        FakeMetrics metrics = new FakeMetrics(WALL_T0 + 1_001);
        metrics.dnsStart = 1L;
        metrics.dnsEnd = 5_004L;
        metrics.connectStart = 5_004L;
        metrics.connectEnd = 5_074L;
        metrics.sslStart = 5_030L;
        metrics.sslEnd = 5_074L;
        metrics.sendingStart = 5_075L;
        metrics.responseStart = 5_079L;
        Connections.onRequestFinished(new FakeInfo(URL, metrics, "h2"));

        List<VideoStart.Stage> stages = Connections.take(500, 7_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.STAGE_OPENING, stages.get(0).name);
        assertEquals("DNS 5003 ms, connect 70 ms (TLS 44 ms), h2, response 4 ms", stages.get(0).detail);
        assertEquals(6_080, stages.get(0).endMs);
    }

    /**
     * NVHZS7YS: 0.47-0.72 s openings on a connection that may have been one already open. A reused one says so, with no lookup or
     * connect. Not ended yet, a request has no metrics: its opening says what it did.
     */
    @Test
    public void aCronetOpeningOnAReusedConnectionSaysSo() throws IOException {
        Connections.sWallClock = () -> WALL_T0 + mNow.get();
        DataSource source = Connections.timeOpenings(new FakeDataSource(470, null));
        mNow.set(1_000);
        source.open(new DataSpec(Uri.parse(URL)));

        assertNull(Connections.take(500, 3_000).get(0).detail);

        FakeMetrics metrics = new FakeMetrics(WALL_T0 + 1_000);
        metrics.isReused = true;
        metrics.sendingStart = 2L;
        metrics.responseStart = 468L;
        Connections.onRequestFinished(new FakeInfo(URL, metrics, "h3"));

        assertEquals("reused, h3, response 466 ms", Connections.take(500, 3_000).get(0).detail);
    }

    /**
     * The same URL asked again (e.g. a retry): the metrics are the request's made nearest their start. Another URL's (e.g. the API's
     * on the same engine) are no opening's.
     */
    @Test
    public void cronetMetricsGoToTheirOwnOpening() {
        Connections.sWallClock = () -> WALL_T0 + mNow.get();
        Connections.addOpening(1_000, 1_400, "video chunk", URL);
        Connections.addOpening(9_000, 9_300, "video chunk", URL);
        mNow.set(10_000);
        FakeMetrics other = new FakeMetrics(WALL_T0 + 1_000);
        other.isReused = true;
        Connections.onRequestFinished(new FakeInfo("https://www.youtube.com/youtubei/v1/player", other, "h2"));
        FakeMetrics retry = new FakeMetrics(WALL_T0 + 9_002);
        retry.isReused = true;
        Connections.onRequestFinished(new FakeInfo(URL, retry, "h2"));

        List<VideoStart.Stage> stages = Connections.take(500, 10_000);

        assertEquals("video chunk", stages.get(0).detail);
        assertEquals("video chunk, reused, h2", stages.get(1).detail);
    }

    /**
     * A file or an asset isn't a request
     */
    @Test
    public void onlyNetworkOpeningsAreTimed() throws IOException {
        DataSource source = Connections.timeOpenings(new FakeDataSource(1_000, null));
        mNow.set(1_000);
        source.open(new DataSpec(Uri.parse("file:///data/video.mp4")));

        assertTrue(Connections.take(500, 3_000).isEmpty());
    }

    @Test
    public void onlyTheLastResponsesAreKept() {
        for (int i = 0; i < Connections.MAX_RESPONSES + 1; i++) {
            Connections.addResponse(i * 1_000L, i * 1_000L + 500, null);
        }

        assertEquals(Connections.MAX_RESPONSES, Connections.take(0, 100_000).size());
        assertTrue(Connections.take(0, 999).isEmpty());
    }

    /**
     * Its lookup, then the address that connected (TLS included)
     */
    @Test
    public void aConnectSaysItsLookupAndAddress() throws IOException {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        mNow.set(1_002);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(1_070);
        listener.connectEnd(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, Protocol.HTTP_1_1);

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.STAGE_CONNECT, stages.get(0).name);
        assertEquals("DNS 2 ms, IPv4 68 ms, http/1.1", stages.get(0).detail);
        assertEquals(1_000, stages.get(0).startMs);
        assertEquals(1_070, stages.get(0).endMs);
    }

    /**
     * VP4G86E9: each first segment of a SABR start connected again, 100-350 ms, and nothing said to which server or why. The
     * server's number is the video's (as its attempts' hosts), and the protocol tells HTTP/1.1, which loses a connection whose
     * response was closed before its end, from HTTP/2.
     */
    @Test
    public void aConnectSaysItsServerAndProtocol() throws IOException {
        PlayerFailureLog.useForTesting(mFolder.getRoot());
        FormatAttempts.resetForTesting();
        FormatAttempts.onNewVideo(RuntimeEnvironment.getApplication(), "oL_NZPsIf0c");
        FormatAttempts.onRequest("rr2---sn-other.googlevideo.com");
        FormatAttempts.onRequest(HOST);

        try {
            Call call = new OkHttpClient().newCall(new Request.Builder().url("https://" + HOST + "/videoplayback?sabr=1").build());
            EventListener listener = Connections.LISTENER.create(call);
            mNow.set(1_000);
            listener.connectStart(call, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
            mNow.set(1_100);
            listener.connectEnd(call, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, Protocol.HTTP_2);

            assertEquals("server 2, IPv4 100 ms, h2", Connections.take(500, 3_000).get(0).detail);
        } finally {
            FormatAttempts.resetForTesting();
            PlayerFailureLog.useForTesting(null);
        }
    }

    /**
     * A server the video's segments weren't asked of (e.g. the connection is the first request's, made before it's told): no number
     */
    @Test
    public void aConnectToAServerNotAskedHasNoNumber() throws IOException {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(1_050);
        listener.connectEnd(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, Protocol.HTTP_2);

        assertEquals("IPv4 50 ms, h2", Connections.take(500, 3_000).get(0).detail);
    }

    /**
     * 5070 ms on a Shield: an address that doesn't answer costs the connect timeout, then the next one is tried. Told apart from a
     * slow lookup.
     */
    @Test
    public void aDeadAddressIsTimedApartFromTheLookup() throws IOException {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        mNow.set(1_003);
        listener.dnsEnd(mCall, HOST, Arrays.asList(IPV4, IPV6));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(6_003);
        listener.connectFailed(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, null,
                new SocketTimeoutException("connect timed out"));
        listener.connectStart(mCall, new InetSocketAddress(IPV6, 443), Proxy.NO_PROXY);
        mNow.set(6_073);
        listener.connectEnd(mCall, new InetSocketAddress(IPV6, 443), Proxy.NO_PROXY, Protocol.HTTP_1_1);

        List<VideoStart.Stage> stages = Connections.take(500, 7_000);

        assertEquals(1, stages.size());
        assertEquals("DNS 3 ms, IPv4 (at 3 ms) timed out after 5000 ms, IPv6 (at 5003 ms) 70 ms, http/1.1", stages.get(0).detail);
        assertEquals(1_000, stages.get(0).startMs);
        assertEquals(6_073, stages.get(0).endMs);
    }

    /**
     * W6k1qMUmzJs (MD8RQZM2): IPv4 tried only once IPv6 gave up 4 s in, its rows read as a race would have. Each address of several
     * says when it began; one whose TCP connect ended and whose TLS handshake hung says so: OkHttp's fast fallback races the TCP
     * connects only, and the handshake waits the read timeout (the SABR loads' 4 s, not the 5 s connect timeout).
     */
    @Test
    public void aSequenceOfAddressesSaysWhenEachBeganAndWhereItHung() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        mNow.set(1_043);
        listener.dnsEnd(mCall, HOST, Arrays.asList(IPV6, IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV6, 443), Proxy.NO_PROXY);
        mNow.set(1_069);
        listener.secureConnectStart(mCall);
        mNow.set(5_069);
        listener.connectFailed(mCall, new InetSocketAddress(IPV6, 443), Proxy.NO_PROXY, null, new SocketTimeoutException("Read timed out"));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(5_090);
        listener.secureConnectStart(mCall);
        mNow.set(6_119);
        listener.connectEnd(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, Protocol.HTTP_1_1);

        assertEquals("DNS 43 ms, IPv6 (at 43 ms) TLS timed out after 4026 ms, IPv4 (at 4069 ms) 1050 ms, http/1.1",
                Connections.take(500, 7_000).get(0).detail);
    }

    /**
     * A race (fast fallback): the next address 250 ms into the first, which is given up on once it connected. Two going at a TLS
     * handshake's start: which one's TCP connect won isn't told.
     */
    @Test
    public void aRaceOfAddressesSaysWhenEachBegan() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        mNow.set(1_043);
        listener.dnsEnd(mCall, HOST, Arrays.asList(IPV6, IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV6, 443), Proxy.NO_PROXY);
        mNow.set(1_293);
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(1_320);
        listener.secureConnectStart(mCall);
        mNow.set(1_343);
        listener.connectEnd(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, Protocol.HTTP_2);

        assertEquals("DNS 43 ms, IPv6 (at 43 ms) cancelled after 300 ms, IPv4 (at 293 ms) 50 ms, h2",
                Connections.take(500, 3_000).get(0).detail);
    }

    /**
     * 93HXRTB5 G0s4r2KS3ok: the video init chunk's "connect 1474 ms" was a lookup of 840 ms, then the connection the audio init chunk
     * opened meanwhile, taken from the pool: no address of its own, its request sent 1 ms later. It ran to the call's end.
     */
    @Test
    public void aConnectionTakenFromThePoolAfterTheLookupEndsThere() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        mNow.set(1_840);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        mNow.set(1_841);
        listener.connectionAcquired(mCall, pooled(Protocol.HTTP_1_1));
        listener.requestHeadersStart(mCall);
        mNow.set(2_349);
        listener.responseBodyStart(mCall);
        mNow.set(2_474);
        listener.callEnd(mCall);

        VideoStart.Stage connect = Connections.take(500, 3_000).get(0);

        assertEquals(VideoStarts.STAGE_CONNECT, connect.name);
        assertEquals("DNS 840 ms, pooled, http/1.1", connect.detail);
        assertEquals(1_000, connect.startMs);
        assertEquals(1_841, connect.endMs);
    }

    /**
     * The connection the call opened is acquired after its connect ended: nothing more to it
     */
    @Test
    public void aConnectionOfItsOwnIsntPooled() {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        mNow.set(1_002);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(1_070);
        listener.connectEnd(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, Protocol.HTTP_1_1);
        mNow.set(1_071);
        listener.connectionAcquired(mCall, pooled(Protocol.HTTP_1_1));

        VideoStart.Stage connect = Connections.take(500, 3_000).get(0);

        assertEquals("DNS 2 ms, IPv4 68 ms, http/1.1", connect.detail);
        assertEquals(1_070, connect.endMs);
    }

    /**
     * A connection of the pool, as OkHttp gives it: only its protocol is read
     */
    private static okhttp3.Connection pooled(Protocol protocol) {
        return new okhttp3.Connection() {
            @Override
            public okhttp3.Route route() {
                return null;
            }

            @Override
            public java.net.Socket socket() {
                return null;
            }

            @Override
            public okhttp3.Handshake handshake() {
                return null;
            }

            @Override
            public Protocol protocol() {
                return protocol;
            }
        };
    }

    @Test
    public void aSlowLookupIsTheDns() throws IOException {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        mNow.set(6_003);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(6_070);
        listener.connectEnd(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, Protocol.HTTP_1_1);

        assertEquals("DNS 5003 ms, IPv4 67 ms, http/1.1", Connections.take(500, 7_000).get(0).detail);
    }

    /**
     * Looked at while it connects: what it's at
     */
    @Test
    public void oneGoingSaysWhatItsAt() throws IOException {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);

        assertEquals("DNS, " + VideoStarts.DETAIL_GOING, Connections.take(500, 3_000).get(0).detail);

        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);

        assertEquals("DNS 0 ms, IPv4, " + VideoStarts.DETAIL_GOING, Connections.take(500, 3_000).get(0).detail);
    }

    /**
     * Every address failed: the call fails, and the connect ends with them
     */
    @Test
    public void aConnectThatFailsEndsWithItsAttempts() throws IOException {
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(1_020);
        listener.connectFailed(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, null,
                new ConnectException("Connection refused"));
        listener.callFailed(mCall, new ConnectException("Connection refused"));

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals("DNS 0 ms, IPv4 refused after 20 ms", stages.get(0).detail);
        assertEquals(1_020, stages.get(0).endMs);
    }

    /**
     * Nothing ended since the player was prepared: nothing to tell of the lookups
     */
    @Test
    public void noConnectionTellsNothing() {
        Connections.onPrepare();

        assertEquals(Connections.Lookup.UNKNOWN, Connections.getLookup());
    }

    /**
     * A lookup that hangs past the connect timeout (QYZPW0FN #3: lookups still going after 20 s, no load error yet): the stall is
     * the network's
     */
    @Test
    public void aLookupStillGoingIsAStuckConnection() {
        mNow.set(1_000);
        Connections.onPrepare();
        EventListener listener = Connections.LISTENER.create(mCall);
        listener.dnsStart(mCall, HOST);

        mNow.set(1_000 + 4_999);
        assertNull(Connections.getStuckConnection(5_000));

        mNow.set(1_000 + 9_752);
        assertEquals("DNS 9752 ms", Connections.getStuckConnection(5_000));
    }

    @Test
    public void anAddressStillConnectingIsAStuckConnection() {
        mNow.set(1_000);
        Connections.onPrepare();
        EventListener listener = Connections.LISTENER.create(mCall);
        listener.dnsStart(mCall, HOST);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);

        mNow.set(7_000);

        assertEquals("connect 6000 ms", Connections.getStuckConnection(5_000));
    }

    /**
     * One that ended (e.g. a name that didn't resolve) is told by its failure, not as stuck; one of the source before isn't this one's
     */
    @Test
    public void anEndedOrEarlierConnectionIsntStuck() {
        mNow.set(1_000);
        EventListener before = Connections.LISTENER.create(mCall);
        before.dnsStart(mCall, HOST);
        mNow.set(2_000);
        Connections.onPrepare();
        EventListener ended = Connections.LISTENER.create(mCall);
        ended.dnsStart(mCall, HOST);
        ended.callFailed(mCall, new UnknownHostException(HOST));

        mNow.set(20_000);

        assertNull(Connections.getStuckConnection(5_000));
    }

    @Test
    public void aNameThatDidntResolveIsAFailingLookup() {
        Connections.onPrepare();
        EventListener listener = Connections.LISTENER.create(mCall);
        listener.dnsStart(mCall, HOST);
        listener.callFailed(mCall, new UnknownHostException(HOST));

        assertEquals(Connections.Lookup.FAILING, Connections.getLookup());
    }

    @Test
    public void addressesThatAllFailAreAFailingLookup() throws IOException {
        Connections.onPrepare();
        failEveryAddress(Connections.LISTENER.create(mCall));

        assertEquals(Connections.Lookup.FAILING, Connections.getLookup());
    }

    /**
     * One connected: the lookup gave an address that answers, whatever failed before it
     */
    @Test
    public void oneThatConnectedIsAWorkingLookup() throws IOException {
        Connections.onPrepare();
        failEveryAddress(Connections.LISTENER.create(mCall));
        EventListener listener = Connections.LISTENER.create(mCall);
        listener.dnsStart(mCall, HOST);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV6));
        listener.connectStart(mCall, new InetSocketAddress(IPV6, 443), Proxy.NO_PROXY);
        listener.connectEnd(mCall, new InetSocketAddress(IPV6, 443), Proxy.NO_PROXY, Protocol.HTTP_2);
        listener.callEnd(mCall);

        assertEquals(Connections.Lookup.WORKING, Connections.getLookup());
    }

    /**
     * Cancelled (e.g. by a seek) or still going: they tell nothing
     */
    @Test
    public void aCancelledOrGoingOneTellsNothing() throws IOException {
        Connections.onPrepare();
        EventListener cancelled = Connections.LISTENER.create(mCall);
        cancelled.dnsStart(mCall, HOST);
        cancelled.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        cancelled.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        cancelled.connectFailed(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, null, new IOException("Canceled"));
        cancelled.callFailed(mCall, new IOException("Canceled"));
        EventListener going = Connections.LISTENER.create(mCall);
        going.dnsStart(mCall, HOST);

        assertEquals(Connections.Lookup.UNKNOWN, Connections.getLookup());
    }

    /**
     * QYZPW0FN: a reload's connections were at "DNS, still going" for 9.7 s, and the stall after it went by "unknown" and switched
     * the client on a network that was down. A lookup that has gone on as long as an address that doesn't answer is one that fails.
     */
    @Test
    public void aLookupThatGoesOnIsAFailingOne() {
        Connections.onPrepare();
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        mNow.set(1_000 + Connections.LOOKUP_STUCK_MS - 1);

        assertEquals(Connections.Lookup.UNKNOWN, Connections.getLookup());

        mNow.set(1_000 + Connections.LOOKUP_STUCK_MS);

        assertEquals(Connections.Lookup.FAILING, Connections.getLookup());
    }

    /**
     * Its lookup answered, and it connects still: the address may be slow, the name resolved
     */
    @Test
    public void aConnectThatGoesOnTellsNothing() {
        Connections.onPrepare();
        EventListener listener = Connections.LISTENER.create(mCall);
        mNow.set(1_000);
        listener.dnsStart(mCall, HOST);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        mNow.set(1_000 + 2 * Connections.LOOKUP_STUCK_MS);

        assertEquals(Connections.Lookup.UNKNOWN, Connections.getLookup());
    }

    /**
     * Made before the player was prepared again, they're of the source before
     */
    @Test
    public void theOnesOfTheSourceBeforeTellNothing() throws IOException {
        failEveryAddress(Connections.LISTENER.create(mCall));
        mNow.set(5_000);
        Connections.onPrepare();

        assertEquals(Connections.Lookup.UNKNOWN, Connections.getLookup());
    }

    private void failEveryAddress(EventListener listener) {
        listener.dnsStart(mCall, HOST);
        listener.dnsEnd(mCall, HOST, Collections.singletonList(IPV4));
        listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
        listener.connectFailed(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, null,
                new SocketTimeoutException("connect timed out"));
        listener.callFailed(mCall, new SocketTimeoutException("connect timed out"));
    }

    @Test
    public void onlyTheOnesOfTheStartCutToIt() {
        Connections.add(1_000, 2_000, null);
        Connections.add(4_000, 6_000, null);
        Connections.add(9_000, 12_000, null);

        List<VideoStart.Stage> stages = Connections.take(5_000, 10_000);

        assertEquals(2, stages.size());
        assertEquals(VideoStarts.STAGE_CONNECT, stages.get(0).name);
        assertEquals(1, stages.get(0).depth);
        assertEquals(5_000, stages.get(0).startMs);
        assertEquals(6_000, stages.get(0).endMs);
        assertEquals(9_000, stages.get(1).startMs);
        assertEquals(10_000, stages.get(1).endMs);
    }

    @Test
    public void onlyTheLastOnesAreKept() {
        for (int i = 0; i < Connections.MAX_CONNECTIONS + 1; i++) {
            Connections.add(i * 10L, i * 10L + 5, null);
        }

        assertEquals(Connections.MAX_CONNECTIONS, Connections.take(0, 1_000).size());
        assertTrue(Connections.take(0, 5).isEmpty());
    }

    /**
     * MCY8QBR1: its rows said a start connected again and that a request waited 1.4 s for the server, not for which load: the audio
     * init's wait was told from the video init's only by when each ended. A call is made on its loader's thread, which tells it.
     */
    @Test
    public void aCallNamesTheLoadItIsMadeIn() throws Exception {
        load(new TestChunk(C.DATA_TYPE_MEDIA_INITIALIZATION, AUDIO, () -> {
            EventListener listener = Connections.LISTENER.create(mCall);
            mNow.set(1_000);
            listener.connectStart(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY);
            mNow.set(1_050);
            listener.connectEnd(mCall, new InetSocketAddress(IPV4, 443), Proxy.NO_PROXY, Protocol.HTTP_1_1);
            listener.requestHeadersStart(mCall);
            mNow.set(2_450);
            listener.responseBodyStart(mCall);
            listener.callEnd(mCall);
        }));

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals(2, stages.size());
        assertEquals(VideoStarts.STAGE_CONNECT, stages.get(0).name);
        assertEquals("audio init chunk, IPv4 50 ms, http/1.1", stages.get(0).detail);
        assertEquals(VideoStarts.STAGE_RESPONSE, stages.get(1).name);
        assertEquals("audio init chunk", stages.get(1).detail);
        assertEquals(1_050, stages.get(1).startMs);
        assertEquals(2_450, stages.get(1).endMs);
    }

    /**
     * The other engines' requests are made on their loader's thread too
     */
    @Test
    public void anOpeningNamesItsLoad() throws Exception {
        DataSource source = Connections.timeOpenings(new FakeDataSource(1_400, null));
        mNow.set(1_000);
        load(new TestChunk(C.DATA_TYPE_MEDIA, VIDEO, () -> source.open(new DataSpec(Uri.parse("https://example.com/videoplayback")))));

        List<VideoStart.Stage> stages = Connections.take(500, 3_000);

        assertEquals(1, stages.size());
        assertEquals(VideoStarts.STAGE_OPENING, stages.get(0).name);
        assertEquals("video chunk", stages.get(0).detail);
    }

    /**
     * Its load, then what it's waiting for
     */
    @Test
    public void oneWaitingSaysItsLoadThenStillGoing() {
        Connections.addResponse(1_000, -1, "audio init chunk");

        assertEquals("audio init chunk, " + VideoStarts.DETAIL_GOING, Connections.take(500, 3_000).get(0).detail);
    }

    @Test
    public void aLoadIsNamedByItsTrackAndKind() {
        assertEquals("video init chunk", Connections.describeLoad(new TestChunk(C.DATA_TYPE_MEDIA_INITIALIZATION, VIDEO, null)));
        assertEquals("audio chunk", Connections.describeLoad(new TestChunk(C.DATA_TYPE_MEDIA, AUDIO, null)));
        // Told by the container when the sample's type isn't known
        assertEquals("audio chunk", Connections.describeLoad(new TestChunk(C.DATA_TYPE_MEDIA,
                Format.createContainerFormat("140", "audio/mp4", null, null, Format.NO_VALUE, 0, null), null)));
        assertEquals("subtitle chunk", Connections.describeLoad(new TestChunk(C.DATA_TYPE_MEDIA,
                Format.createContainerFormat("en", null, "text/vtt", null, Format.NO_VALUE, 0, "en"), null)));
        // A muxed one has no track of its own
        assertEquals("chunk", Connections.describeLoad(new TestChunk(C.DATA_TYPE_MEDIA,
                Format.createContainerFormat("0", "application/x-mpegURL", null, null, Format.NO_VALUE, 0, null), null)));
        assertEquals("manifest", Connections.describeLoad(new ParsingLoadable<>(new FakeDataSource(0, null),
                Uri.parse("https://example.com/manifest.mpd"), C.DATA_TYPE_MANIFEST, (uri, inputStream) -> null)));
        // Not a loader's thread
        assertNull(Connections.describeLoad(null));
    }

    /**
     * Loaded by a real Loader, as the player's segments are: on the loader's own thread
     */
    private static void load(TestChunk chunk) throws Exception {
        Loader loader = new Loader("ConnectionsTest");

        try {
            loader.startLoading(chunk, new Loader.Callback<TestChunk>() {
                @Override
                public void onLoadCompleted(TestChunk loadable, long elapsedRealtimeMs, long loadDurationMs) {
                }

                @Override
                public void onLoadCanceled(TestChunk loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {
                }

                @Override
                public Loader.LoadErrorAction onLoadError(TestChunk loadable, long elapsedRealtimeMs, long loadDurationMs,
                                                          IOException error, int errorCount) {
                    return Loader.DONT_RETRY;
                }
            }, 0);

            chunk.await();
        } finally {
            loader.release();
        }
    }

    private interface LoadBody {
        void run() throws IOException;
    }

    /**
     * A segment whose load runs the body (none: never loaded), and tells when it's done
     */
    private static final class TestChunk extends Chunk {
        @Nullable
        private final LoadBody mBody;
        private final CountDownLatch mDone = new CountDownLatch(1);
        private final AtomicReference<Throwable> mError = new AtomicReference<>();

        TestChunk(int type, Format format, @Nullable LoadBody body) {
            super(new UnopenedDataSource(), new DataSpec(Uri.parse("https://example.com/videoplayback")), type, format,
                    C.SELECTION_REASON_UNKNOWN, null, 0, 5_000_000);
            mBody = body;
        }

        @Override
        public void cancelLoad() {
        }

        @Override
        public void load() {
            try {
                if (mBody != null) {
                    mBody.run();
                }
            } catch (Throwable e) {
                mError.set(e);
            } finally {
                mDone.countDown();
            }
        }

        void await() throws Exception {
            assertTrue("not loaded", mDone.await(5, TimeUnit.SECONDS));

            if (mError.get() != null) {
                throw new AssertionError(mError.get());
            }
        }
    }

    /**
     * A chunk's own source, never opened here: the body makes the requests
     */
    private static final class UnopenedDataSource implements DataSource {
        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public long open(DataSpec dataSpec) {
            return C.LENGTH_UNSET;
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) {
            return C.RESULT_END_OF_INPUT;
        }

        @Override
        public Uri getUri() {
            return null;
        }

        @Override
        public void close() {
        }
    }

    private static InetAddress address(byte[] bytes) {
        try {
            return InetAddress.getByAddress(bytes);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Cronet's metrics of a request: its start on the wall clock, the others in ms since it (null for none)
     */
    private static final class FakeMetrics extends RequestFinishedInfo.Metrics {
        private final long mRequestStartMs;
        Long dnsStart;
        Long dnsEnd;
        Long connectStart;
        Long connectEnd;
        Long sslStart;
        Long sslEnd;
        Long sendingStart;
        Long responseStart;
        boolean isReused;

        FakeMetrics(long requestStartMs) {
            mRequestStartMs = requestStartMs;
        }

        @Nullable
        private Date at(@Nullable Long sinceStartMs) {
            return sinceStartMs != null ? new Date(mRequestStartMs + sinceStartMs) : null;
        }

        @Override
        public Date getRequestStart() {
            return new Date(mRequestStartMs);
        }

        @Override
        public Date getDnsStart() {
            return at(dnsStart);
        }

        @Override
        public Date getDnsEnd() {
            return at(dnsEnd);
        }

        @Override
        public Date getConnectStart() {
            return at(connectStart);
        }

        @Override
        public Date getConnectEnd() {
            return at(connectEnd);
        }

        @Override
        public Date getSslStart() {
            return at(sslStart);
        }

        @Override
        public Date getSslEnd() {
            return at(sslEnd);
        }

        @Override
        public Date getSendingStart() {
            return at(sendingStart);
        }

        @Override
        public Date getSendingEnd() {
            return at(sendingStart);
        }

        @Override
        public Date getPushStart() {
            return null;
        }

        @Override
        public Date getPushEnd() {
            return null;
        }

        @Override
        public Date getResponseStart() {
            return at(responseStart);
        }

        @Override
        public Date getRequestEnd() {
            return null;
        }

        @Override
        public boolean getSocketReused() {
            return isReused;
        }

        @Override
        public Long getTtfbMs() {
            return null;
        }

        @Override
        public Long getTotalTimeMs() {
            return null;
        }

        @Override
        public Long getSentByteCount() {
            return null;
        }

        @Override
        public Long getReceivedByteCount() {
            return null;
        }
    }

    /**
     * A request of Cronet's that ended, as its engine tells it (see CronetManager#setRequestFinishedListener)
     */
    private static final class FakeInfo extends RequestFinishedInfo {
        private final String mUrl;
        private final Metrics mMetrics;
        private final String mProtocol;

        FakeInfo(String url, Metrics metrics, String protocol) {
            mUrl = url;
            mMetrics = metrics;
            mProtocol = protocol;
        }

        @Override
        public String getUrl() {
            return mUrl;
        }

        @Override
        public Collection<Object> getAnnotations() {
            return Collections.emptyList();
        }

        @Override
        public Metrics getMetrics() {
            return mMetrics;
        }

        @Override
        public int getFinishedReason() {
            return 0;
        }

        @Override
        public UrlResponseInfo getResponseInfo() {
            return new UrlResponseInfo() {
                @Override
                public String getUrl() {
                    return mUrl;
                }

                @Override
                public List<String> getUrlChain() {
                    return Collections.singletonList(mUrl);
                }

                @Override
                public int getHttpStatusCode() {
                    return 200;
                }

                @Override
                public String getHttpStatusText() {
                    return "OK";
                }

                @Override
                public List<Map.Entry<String, String>> getAllHeadersAsList() {
                    return Collections.emptyList();
                }

                @Override
                public Map<String, List<String>> getAllHeaders() {
                    return Collections.emptyMap();
                }

                @Override
                public boolean wasCached() {
                    return false;
                }

                @Override
                public String getNegotiatedProtocol() {
                    return mProtocol;
                }

                @Override
                public String getProxyServer() {
                    return null;
                }

                @Override
                public long getReceivedByteCount() {
                    return 0;
                }
            };
        }

        @Override
        public CronetException getException() {
            return null;
        }
    }

    /**
     * Its open takes that long on the test's clock, then throws the error if any
     */
    private final class FakeDataSource implements DataSource {
        private final long mOpenMs;
        @Nullable
        private final IOException mError;

        FakeDataSource(long openMs, @Nullable IOException error) {
            mOpenMs = openMs;
            mError = error;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            mNow.addAndGet(mOpenMs);

            if (mError != null) {
                throw mError;
            }

            return C.LENGTH_UNSET;
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) {
            return C.RESULT_END_OF_INPUT;
        }

        @Override
        public Uri getUri() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
