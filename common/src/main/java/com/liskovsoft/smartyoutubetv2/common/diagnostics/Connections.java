package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ext.cronet.CronetDataSource;
import com.google.android.exoplayer2.source.chunk.Chunk;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.Loader;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Proxy;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLException;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.Protocol;

import org.chromium.net.RequestFinishedInfo;
import org.chromium.net.UrlResponseInfo;

/**
 * The connections the player's OkHttp client opened, from the name's lookup to the connection being ready (with the lookup's time and
 * each address tried in their detail), and its requests that waited long for their answer, from being sent to its first byte. A
 * segment that took long with no response in its time was slow to come, one with a long response waited for the server. The other
 * engines' requests are timed whole, from being made to their answer's headers, connect included (see timeOpenings), Cronet's with
 * where that time went once they ended (see onRequestFinished). Each names the load it's of, e.g. the video's init chunk (see
 * describeLoad). Any thread: OkHttp's, Cronet's and the loaders'.
 */
public final class Connections {
    /**
     * The last ones: a start only needs the ones since it was prepared
     */
    static final int MAX_CONNECTIONS = 8;
    /**
     * A start has a request for each first segment, and more while it buffers
     */
    static final int MAX_RESPONSES = 16;
    /**
     * Shorter, a response says nothing more than none: the server answered at once. Each request of a start's buffering shown, its
     * segments' rows went past what the graph shows (see StartGraphView#MAX_ROWS).
     */
    static final long MIN_RESPONSE_MS = 200;
    /**
     * Of an address given up on: another connected first, or the call was cancelled
     */
    static final String FAILURE_CANCELLED = "cancelled";
    /**
     * A lookup still going this long is one that fails (see isLookupStuck): as long as an address that doesn't answer is given (the
     * connect timeout, see OkHttpCommons)
     */
    static final long LOOKUP_STUCK_MS = 5_000;
    /**
     * Of a load (see describeLoad): the format's header, after its track, e.g. "video init chunk"
     */
    static final String LOAD_INIT_CHUNK = "init chunk";
    /**
     * Of a load (see describeLoad): a segment of the media, after its track, e.g. "audio chunk"
     */
    static final String LOAD_CHUNK = "chunk";
    /**
     * Of a load (see describeLoad): a DASH manifest or an HLS playlist
     */
    static final String LOAD_MANIFEST = "manifest";
    /**
     * Of a connection whose call took one from the pool after its lookup, no address tried (see Listener#connectionAcquired)
     */
    static final String POOLED = "pooled";
    /**
     * Cronet starts a request as it's opened (see CronetDataSource#open): one of the same URL made further from an opening is another
     * (see onRequestFinished)
     */
    static final long MAX_OPENING_MATCH_MS = 1_000;

    /**
     * For the client of the player only: the API's connections aren't the video's. Each call is made on the thread of the load it's
     * of (e.g. OkHttpDataSource#open), and its listener with it: that's when the load is known.
     */
    public static final EventListener.Factory LISTENER = call -> new Listener(describeLoad(Loader.getCurrentLoadable()));
    private static final ArrayDeque<Connection> sConnections = new ArrayDeque<>();
    private static final ArrayDeque<TimedRequest> sResponses = new ArrayDeque<>();
    private static final ArrayDeque<TimedRequest> sOpenings = new ArrayDeque<>();
    @VisibleForTesting
    static VideoStarts.Clock sClock = SystemClock::elapsedRealtime;
    /**
     * Cronet's metrics are dates (see onRequestFinished)
     */
    @VisibleForTesting
    static VideoStarts.Clock sWallClock = System::currentTimeMillis;
    /**
     * When the player was last prepared with a source, in the clock's time, -1 for not yet (see getLookup)
     */
    private static long sPreparedMs = -1;

    /**
     * What the player's new connections since it was prepared say of their name's lookup (see getLookup)
     */
    public enum Lookup {
        /**
         * None ended, or none made by OkHttp (the default and Cronet engines tell nothing of their connections, see timeOpenings):
         * nothing to tell
         */
        UNKNOWN,
        /**
         * One connected: the lookup gave an address that answers. A hang isn't the DNS's.
         */
        WORKING,
        /**
         * Each one that ended failed in its lookup, or at every address it gave, or one's lookup has gone on with no answer (see
         * LOOKUP_STUCK_MS): the DNS can be what fails (or the network: told by the formats' fetch, which connected)
         */
        FAILING
    }

    private Connections() {
    }

    /**
     * A new connection: its name's lookup, then each address tried till one connects. OkHttp tries the next when one fails (e.g. after
     * the connect timeout), and 250 ms into one that hasn't connected yet. Its fields change on OkHttp's threads (an attempt has its
     * own), and are read by take: under the class's lock.
     */
    @VisibleForTesting
    static final class Connection {
        final long startMs;
        /**
         * Of the call that opened it (see describeLoad), null for one not known
         */
        @Nullable
        final String load;
        long endMs = -1;
        boolean isDnsStarted;
        /**
         * The name's lookup ended, -1 for none or one going on
         */
        long dnsEndMs = -1;
        /**
         * The addresses tried, in the order they were: e.g. "IPv4 timed out after 5000 ms", "IPv6 70 ms". They can overlap: OkHttp 5
         * starts the next 250 ms into one whose TCP connect hasn't ended yet (fast fallback). Its TLS handshake isn't raced: one that
         * hangs there holds the next one back till the read timeout (see Attempt#tlsStartMs).
         */
        final List<Attempt> attempts = new ArrayList<>(2);
        /**
         * The server's name, only to number it as the video's (see FormatAttempts#getHostNumber): it isn't recorded
         */
        @Nullable
        String host;
        /**
         * What it agreed on with the server, once connected: e.g. HTTP/1.1, where a response closed before its end loses the connection
         */
        @Nullable
        Protocol protocol;
        /**
         * Its call was cancelled (e.g. a seek's): where it ended (its lookup, an address) says nothing of them (see getLookup)
         */
        boolean isCancelled;
        /**
         * Its call took a connection from the pool after its lookup, one another call opened meanwhile: no address of its own connected
         * (see Listener#connectionAcquired)
         */
        boolean isPooled;

        Connection(long startMs, @Nullable String load) {
            this.startMs = startMs;
            this.load = load;
        }

        /**
         * E.g. "video init chunk, server 1, DNS 2 ms, IPv4 70 ms, h2", "audio chunk, DNS 5003 ms, IPv4 68 ms, http/1.1", "DNS 1 ms,
         * IPv4 (at 1 ms) timed out after 5000 ms, IPv6 (at 5001 ms) 70 ms" (its load not known), "video init chunk, DNS 840 ms, pooled,
         * http/1.1". With "still going" for one that hasn't ended, after what it's at: "DNS, still going", "DNS 2 ms, IPv4, still
         * going". Null for nothing known.
         *
         * @param hostNumber of its server among the video's (see FormatAttempts#getHostNumber), -1 for one not known
         */
        @Nullable
        String describe(boolean isGoing, int hostNumber) {
            List<String> parts = new ArrayList<>(attempts.size() + 5);

            if (load != null) {
                parts.add(load);
            }

            if (hostNumber > 0) {
                parts.add("server " + hostNumber);
            }

            // Its load and its server say nothing of the connection itself
            int named = parts.size();

            if (dnsEndMs >= 0) {
                parts.add("DNS " + (dnsEndMs - startMs) + " ms");
            } else if (isDnsStarted) {
                // Ended in its lookup: the name didn't resolve (OkHttp tells no end of a lookup that fails)
                parts.add(isGoing ? "DNS" : "DNS failed after " + (endMs - startMs) + " ms");
            }

            // When each began tells a race from a sequence: W6k1qMUmzJs (MD8RQZM2) tried IPv4 only once IPv6 gave up 4 s in, and its
            // rows read the same as a race would have
            boolean isSeveral = attempts.size() > 1;

            for (Attempt attempt : attempts) {
                parts.add(attempt.describe(isSeveral ? startMs : -1));
            }

            if (isPooled) {
                parts.add(POOLED);
            }

            if (protocol != null) {
                parts.add(protocol.toString());
            }

            if (isGoing) {
                parts.add(VideoStarts.DETAIL_GOING);
            }

            return parts.size() > named ? TextUtils.join(", ", parts) : load;
        }
    }

    /**
     * A request that waited for its answer: a response (from being sent to its first byte) or an opening (from being made to its
     * answer's headers). Its end changes under the class's lock.
     */
    @VisibleForTesting
    static final class TimedRequest {
        final long startMs;
        /**
         * -1 while it waits
         */
        long endMs;
        /**
         * Of the load it's of (see describeLoad), null for one not known
         */
        @Nullable
        final String load;
        /**
         * Why its answer didn't come, e.g. "timed out" (see describeFailure), null for one that came or waits
         */
        @Nullable
        String failure;
        /**
         * Of an opening: what it asked for, to find it by when Cronet tells its metrics (see onRequestFinished). Null for a response.
         */
        @Nullable
        final String url;
        /**
         * Of an opening on Cronet: where its time went once its request ended, e.g. "DNS 2 ms, connect 70 ms (TLS 45 ms), h2, response
         * 230 ms" (see describeTimings). Null till then, and on the other engines.
         */
        @Nullable
        String timings;

        TimedRequest(long startMs, long endMs, @Nullable String load) {
            this(startMs, endMs, load, null);
        }

        TimedRequest(long startMs, long endMs, @Nullable String load, @Nullable String url) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.load = load;
            this.url = url;
        }
    }

    /**
     * An address tried. Its end changes under the class's lock.
     */
    static final class Attempt {
        final InetSocketAddress address;
        final String family;
        final long startMs;
        long endMs = -1;
        /**
         * Why it didn't connect, e.g. "timed out", null for one that did or is going
         */
        @Nullable
        String failure;
        /**
         * Its TCP connect ended and its TLS handshake began then, -1 for not known (one not there, or another address going too: which of
         * them got there isn't told). A failure from then on is the handshake's, which OkHttp's fast fallback doesn't race: it waits the
         * read timeout (W6k1qMUmzJs, MD8RQZM2: "IPv6 timed out after 4026 ms", the SABR loads' 4 s, not the 5 s connect timeout, and
         * only then IPv4).
         */
        long tlsStartMs = -1;

        Attempt(InetSocketAddress address, String family, long startMs) {
            this.address = address;
            this.family = family;
            this.startMs = startMs;
        }

        /**
         * E.g. "IPv4 70 ms", "IPv6 timed out after 5000 ms", "IPv6 TLS timed out after 4026 ms", just "IPv4" for one going. With when
         * it began, for one of several: "IPv6 (at 43 ms) TLS timed out after 4026 ms", "IPv4 (at 4069 ms) 1050 ms".
         *
         * @param connectionStartMs of its connection, -1 to leave when it began out
         */
        String describe(long connectionStartMs) {
            String name = connectionStartMs >= 0 ? family + " (at " + (startMs - connectionStartMs) + " ms)" : family;

            if (endMs < 0) {
                return name;
            }

            long durationMs = endMs - startMs;

            if (failure == null) {
                return name + " " + durationMs + " ms";
            }

            String failed = tlsStartMs >= 0 && !failure.startsWith("TLS") ? "TLS " + failure : failure;

            return name + " " + failed + " after " + durationMs + " ms";
        }
    }

    /**
     * Of a call. Its dead ends count: they're the time it took to connect.
     */
    private static final class Listener extends EventListener {
        /**
         * Of the call (see describeLoad), null for one not known
         */
        @Nullable
        private final String mLoad;
        private volatile Connection mConnection;
        private volatile TimedRequest mResponse;

        Listener(@Nullable String load) {
            mLoad = load;
        }

        private void onStart(Call call) {
            if (mConnection == null) {
                mConnection = add(sClock.now(), -1, mLoad);
                setHost(mConnection, call.request().url().host());
            }
        }

        private void onEnd() {
            Connection connection = mConnection;

            if (connection != null) {
                end(connection);
                mConnection = null;
            }
        }

        private void onResponseEnd() {
            onResponseEnd(null);
        }

        /**
         * @param failure why its answer didn't come, null for one that did
         */
        private void onResponseEnd(@Nullable IOException failure) {
            TimedRequest response = mResponse;

            if (response != null) {
                end(response, failure);
                mResponse = null;
            }
        }

        @Override
        public void dnsStart(@NonNull Call call, @NonNull String domainName) {
            onStart(call);
            startDns(mConnection);
        }

        @Override
        public void dnsEnd(@NonNull Call call, @NonNull String domainName, @NonNull List<InetAddress> inetAddressList) {
            Connection connection = mConnection;

            if (connection != null) {
                endDns(connection);
            }
        }

        @Override
        public void connectStart(@NonNull Call call, @NonNull InetSocketAddress inetSocketAddress, @NonNull Proxy proxy) {
            onStart(call);
            startAttempt(mConnection, inetSocketAddress);
        }

        /**
         * OkHttp tries the next address, if there's one
         */
        @Override
        public void connectFailed(@NonNull Call call, @NonNull InetSocketAddress inetSocketAddress, @NonNull Proxy proxy,
                                  @Nullable Protocol protocol, @NonNull IOException ioe) {
            Connection connection = mConnection;

            if (connection != null) {
                endAttempt(connection, inetSocketAddress, describeFailure(ioe));
            }
        }

        @Override
        public void secureConnectStart(@NonNull Call call) {
            Connection connection = mConnection;

            if (connection != null) {
                startTls(connection);
            }
        }

        @Override
        public void connectEnd(@NonNull Call call, @NonNull InetSocketAddress inetSocketAddress, @NonNull Proxy proxy, Protocol protocol) {
            Connection connection = mConnection;

            if (connection != null) {
                setProtocol(connection, protocol);
                endAttempt(connection, inetSocketAddress, null);
            }

            onEnd();
        }

        /**
         * Told after connectEnd of a connection the call opened, which ended it. One that hasn't ended is the pool's: another call opened
         * it while this one looked the name up (93HXRTB5 G0s4r2KS3ok: the video init chunk's "connect 1474 ms" was a lookup of 840 ms,
         * then the audio init chunk's connection, its request sent 1 ms later; it ran to the call's end), or while its own addresses
         * were being tried.
         */
        @Override
        public void connectionAcquired(@NonNull Call call, @NonNull okhttp3.Connection connection) {
            Connection timed = mConnection;

            if (timed != null) {
                endPooled(timed, connection.protocol());
                mConnection = null;
            }
        }

        /**
         * A follow-up of the call (e.g. a redirect) ends the response before: its body isn't read
         */
        @Override
        public void requestHeadersStart(@NonNull Call call) {
            onResponseEnd();
            mResponse = addResponse(sClock.now(), -1, mLoad);
        }

        /**
         * Told after the first read of the body returned (OkHttp 4): its first byte came
         */
        @Override
        public void responseBodyStart(@NonNull Call call) {
            onResponseEnd();
        }

        @Override
        public void requestFailed(@NonNull Call call, @NonNull IOException ioe) {
            onResponseEnd(ioe);
        }

        /**
         * E.g. its headers timed out (S4PGCB6X: a video chunk's "response 4002" twice, OkHttp's read timeout)
         */
        @Override
        public void responseFailed(@NonNull Call call, @NonNull IOException ioe) {
            onResponseEnd(ioe);
        }

        @Override
        public void callEnd(@NonNull Call call) {
            onEnd();
            onResponseEnd();
        }

        @Override
        public void callFailed(@NonNull Call call, @NonNull IOException ioe) {
            Connection connection = mConnection;

            if (connection != null && FAILURE_CANCELLED.equals(describeFailure(ioe))) {
                setCancelled(connection);
            }

            onEnd();
            onResponseEnd(ioe);
        }
    }

    /**
     * The player was prepared with a source (e.g. another video, or the same one again by a reload)
     */
    public static synchronized void onPrepare() {
        sPreparedMs = sClock.now();
    }

    /**
     * What the player's new connections since it was prepared say of their name's lookup. One cancelled (e.g. a seek's, or another
     * address that connected first) tells nothing.
     */
    public static synchronized Lookup getLookup() {
        boolean isFailing = false;
        long nowMs = sClock.now();

        for (Connection connection : sConnections) {
            if (connection.startMs < sPreparedMs) {
                continue;
            }

            if (isConnected(connection)) {
                return Lookup.WORKING;
            }

            isFailing |= isLookupFailed(connection) || isEveryAddressFailed(connection) || isLookupStuck(connection, nowMs);
        }

        return isFailing ? Lookup.FAILING : Lookup.UNKNOWN;
    }

    /**
     * Of the player's new connections since it was prepared, the oldest one still in its name's lookup or still connecting after
     * minMs, e.g. "DNS 9752 ms", "connect 5003 ms": null for none. A stall whose loads hang there is the network's, not the client's
     * (QYZPW0FN #3: lookups still going after 20 s, no load error yet, and the stall switched the client). Only OkHttp's are told.
     */
    @Nullable
    public static synchronized String getStuckConnection(long minMs) {
        long now = sClock.now();
        Connection stuck = null;

        for (Connection connection : sConnections) {
            if (connection.startMs < sPreparedMs || connection.endMs >= 0 || connection.isCancelled || isConnected(connection)
                    || now - connection.startMs < minMs) {
                continue;
            }

            if (stuck == null || connection.startMs < stuck.startMs) {
                stuck = connection;
            }
        }

        if (stuck == null) {
            return null;
        }

        boolean isInLookup = stuck.isDnsStarted && stuck.dnsEndMs < 0;

        return (isInLookup ? "DNS " : "connect ") + (now - stuck.startMs) + " ms";
    }

    private static boolean isConnected(Connection connection) {
        for (Attempt attempt : connection.attempts) {
            if (attempt.endMs >= 0 && attempt.failure == null) {
                return true;
            }
        }

        return false;
    }

    /**
     * It ended in its lookup: the name didn't resolve (OkHttp tells no end of a lookup that fails)
     */
    private static boolean isLookupFailed(Connection connection) {
        return connection.endMs >= 0 && !connection.isCancelled && connection.isDnsStarted && connection.dnsEndMs < 0;
    }

    /**
     * Its lookup has gone on this long with no answer: the name doesn't resolve, and the resolver hasn't said so yet. Going on, it told
     * nothing (QYZPW0FN: a reload's connections were at "DNS, still going" for 9.7 s when it was given up; the stall 26 s later, #3,
     * went by "unknown" and switched the client on a network that was down, and www.youtube.com didn't resolve 21 s after that).
     */
    private static boolean isLookupStuck(Connection connection, long nowMs) {
        return connection.endMs < 0 && connection.isDnsStarted && connection.dnsEndMs < 0
                && nowMs - connection.startMs >= LOOKUP_STUCK_MS;
    }

    /**
     * It ended, each address the lookup gave tried and failed (not cancelled): none answers
     */
    private static boolean isEveryAddressFailed(Connection connection) {
        if (connection.endMs < 0 || connection.isCancelled || connection.attempts.isEmpty()) {
            return false;
        }

        for (Attempt attempt : connection.attempts) {
            if (attempt.endMs < 0 || attempt.failure == null || FAILURE_CANCELLED.equals(attempt.failure)) {
                return false;
            }
        }

        return true;
    }

    @VisibleForTesting
    static synchronized Connection add(long startMs, long endMs, @Nullable String load) {
        Connection connection = new Connection(startMs, load);
        connection.endMs = endMs;
        sConnections.addLast(connection);

        if (sConnections.size() > MAX_CONNECTIONS) {
            sConnections.removeFirst();
        }

        return connection;
    }

    @VisibleForTesting
    static synchronized TimedRequest addResponse(long sentMs, long firstByteMs, @Nullable String load) {
        TimedRequest response = new TimedRequest(sentMs, firstByteMs, load);
        sResponses.addLast(response);

        if (sResponses.size() > MAX_RESPONSES) {
            sResponses.removeFirst();
        }

        return response;
    }

    /**
     * For the player's engines with no OkHttp client (the default one, Cronet): their connections and responses aren't told apart, a
     * request is timed whole, its open, from being made to its answer's headers. One that fails to open (e.g. a connect timeout) or is
     * cancelled ends then. Cronet tells where the time went once the request ended (see onRequestFinished).
     */
    public static DataSource timeOpenings(DataSource dataSource) {
        return new OpeningTimer(dataSource);
    }

    private static final class OpeningTimer implements DataSource {
        private final DataSource mDataSource;

        OpeningTimer(DataSource dataSource) {
            mDataSource = dataSource;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            mDataSource.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            // Made on the thread of the load it's of
            TimedRequest opening = isNetwork(dataSpec)
                    ? addOpening(sClock.now(), -1, describeLoad(Loader.getCurrentLoadable()), dataSpec.uri.toString()) : null;
            IOException failure = null;

            try {
                return mDataSource.open(dataSpec);
            } catch (IOException e) {
                // E.g. Cronet's connect timed out (S4PGCB6X #4)
                failure = e;
                throw e;
            } finally {
                if (opening != null) {
                    end(opening, failure);
                }
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            return mDataSource.read(buffer, offset, readLength);
        }

        @Nullable
        @Override
        public Uri getUri() {
            return mDataSource.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return mDataSource.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            mDataSource.close();
        }

        /**
         * A file or an asset isn't a request
         */
        private static boolean isNetwork(DataSpec dataSpec) {
            String scheme = dataSpec.uri.getScheme();

            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        }
    }

    /**
     * @param url what it asks for, null for not known
     */
    @VisibleForTesting
    static synchronized TimedRequest addOpening(long madeMs, long headersMs, @Nullable String load, @Nullable String url) {
        TimedRequest opening = new TimedRequest(madeMs, headersMs, load, url);
        sOpenings.addLast(opening);

        if (sOpenings.size() > MAX_RESPONSES) {
            sOpenings.removeFirst();
        }

        return opening;
    }

    /**
     * A request of Cronet's ended (see CronetManager#setRequestFinishedListener in SharedModules), on its listener's thread: its
     * opening says where its time went, as OkHttp's connect rows do (WVE7ZKDD 11-24 01:57:39: both init chunks' openings took 5.08 s,
     * and nothing told the lookup from the connect or the server; NVHZS7YS: 0.47-0.72 s each). Told once the request ended, its body
     * read: an opening whose request still loads has none yet. The API's requests on the engine are none of the openings.
     */
    public static void onRequestFinished(RequestFinishedInfo info) {
        RequestFinishedInfo.Metrics metrics = info.getMetrics();
        String url = info.getUrl();

        if (metrics == null || url == null) {
            return;
        }

        UrlResponseInfo response = info.getResponseInfo();
        String timings = describeTimings(metrics, response != null ? response.getNegotiatedProtocol() : null);
        Date madeAt = metrics.getRequestStart();

        if (timings != null) {
            // Its metrics are dates: on the clock, as the openings are
            setTimings(url, madeAt != null ? sClock.now() - (sWallClock.now() - madeAt.getTime()) : -1, timings);
        }
    }

    /**
     * E.g. "DNS 2 ms, connect 70 ms (TLS 45 ms), h2, response 230 ms", "reused, h3, response 412 ms", "DNS 3 ms, connect" (one that
     * didn't connect). Cronet's connect has the TLS handshake in it. The response is from the request being sent to its answer's
     * headers: the server's wait and a round trip. Null for nothing known.
     *
     * @param protocol the one it agreed on with the server, e.g. h2, h3, http/1.1; null or empty for none
     */
    @VisibleForTesting
    @Nullable
    static String describeTimings(RequestFinishedInfo.Metrics metrics, @Nullable String protocol) {
        List<String> parts = new ArrayList<>(4);

        if (metrics.getSocketReused()) {
            parts.add("reused");
        } else {
            long dnsMs = between(metrics.getDnsStart(), metrics.getDnsEnd());

            if (dnsMs >= 0) {
                parts.add("DNS " + dnsMs + " ms");
            }

            if (metrics.getConnectStart() != null) {
                long connectMs = between(metrics.getConnectStart(), metrics.getConnectEnd());
                long tlsMs = between(metrics.getSslStart(), metrics.getSslEnd());

                parts.add(connectMs < 0 ? "connect" : tlsMs >= 0 ? "connect " + connectMs + " ms (TLS " + tlsMs + " ms)"
                        : "connect " + connectMs + " ms");
            }
        }

        if (!TextUtils.isEmpty(protocol) && !"unknown".equals(protocol)) {
            parts.add(protocol);
        }

        long responseMs = between(metrics.getSendingStart(), metrics.getResponseStart());

        if (responseMs >= 0) {
            parts.add("response " + responseMs + " ms");
        }

        return parts.isEmpty() ? null : TextUtils.join(", ", parts);
    }

    /**
     * -1 for one of them not known
     */
    private static long between(@Nullable Date from, @Nullable Date to) {
        return from != null && to != null ? Math.max(0, to.getTime() - from.getTime()) : -1;
    }

    /**
     * To the opening of the URL made nearest the time, that has none yet
     *
     * @param madeMs when the request was made, in the clock's time, -1 for not known: the oldest one then
     */
    @VisibleForTesting
    static synchronized void setTimings(String url, long madeMs, String timings) {
        TimedRequest match = null;

        for (TimedRequest opening : sOpenings) {
            if (opening.timings != null || !url.equals(opening.url)) {
                continue;
            }

            if (madeMs < 0) {
                match = opening;
                break;
            }

            long offMs = Math.abs(opening.startMs - madeMs);

            if (offMs <= MAX_OPENING_MATCH_MS && (match == null || offMs < Math.abs(match.startMs - madeMs))) {
                match = opening;
            }
        }

        if (match != null) {
            match.timings = timings;
        }
    }

    /**
     * @param failure why its answer didn't come, null for one that did
     */
    private static synchronized void end(TimedRequest request, @Nullable IOException failure) {
        request.endMs = sClock.now();
        request.failure = failure != null ? describeFailure(failure) : null;
    }

    private static synchronized void end(Connection connection) {
        connection.endMs = sClock.now();
    }

    private static synchronized void setCancelled(Connection connection) {
        connection.isCancelled = true;
    }

    private static synchronized void setHost(Connection connection, String host) {
        connection.host = host;
    }

    private static synchronized void setProtocol(Connection connection, @Nullable Protocol protocol) {
        connection.protocol = protocol;
    }

    private static synchronized void startDns(Connection connection) {
        connection.isDnsStarted = true;
    }

    private static synchronized void endDns(Connection connection) {
        connection.dnsEndMs = sClock.now();
    }

    private static synchronized void startAttempt(Connection connection, InetSocketAddress address) {
        connection.attempts.add(new Attempt(address, familyOf(address), sClock.now()));
    }

    /**
     * To the address going, when it's the only one: with several, which one's TCP connect won isn't told (OkHttp cancels the others
     * then, and may tell their end after)
     */
    private static synchronized void startTls(Connection connection) {
        Attempt going = null;

        for (Attempt attempt : connection.attempts) {
            if (attempt.endMs < 0) {
                if (going != null) {
                    return;
                }

                going = attempt;
            }
        }

        if (going != null) {
            going.tlsStartMs = sClock.now();
        }
    }

    /**
     * Its call took a connection from the pool: it ends now, the addresses still going given up on (OkHttp cancels them)
     */
    private static synchronized void endPooled(Connection connection, @Nullable Protocol protocol) {
        long nowMs = sClock.now();
        connection.isPooled = true;
        connection.protocol = protocol;
        connection.endMs = nowMs;

        for (Attempt attempt : connection.attempts) {
            if (attempt.endMs < 0) {
                attempt.endMs = nowMs;
                attempt.failure = FAILURE_CANCELLED;
            }
        }
    }

    /**
     * Ends the last attempt going to the address. One that connected ends the others going: OkHttp cancels them, and may tell their
     * end only after this one's.
     *
     * @param failure why it didn't connect, e.g. "timed out", null for one that did
     */
    private static synchronized void endAttempt(Connection connection, InetSocketAddress address, @Nullable String failure) {
        long nowMs = sClock.now();

        for (int i = connection.attempts.size() - 1; i >= 0; i--) {
            Attempt attempt = connection.attempts.get(i);

            if (attempt.endMs < 0 && attempt.address.equals(address)) {
                attempt.endMs = nowMs;
                attempt.failure = failure;
                break;
            }
        }

        if (failure != null) {
            return;
        }

        for (Attempt attempt : connection.attempts) {
            if (attempt.endMs < 0) {
                attempt.endMs = nowMs;
                attempt.failure = FAILURE_CANCELLED;
            }
        }
    }

    /**
     * IPv4 or IPv6, "address" for one not resolved (e.g. behind a proxy)
     */
    private static String familyOf(InetSocketAddress socketAddress) {
        InetAddress address = socketAddress.getAddress();

        return address instanceof Inet6Address ? "IPv6" : address instanceof Inet4Address ? "IPv4" : "address";
    }

    /**
     * E.g. "timed out": the connect timeout (5 s, see OkHttpCommons) on an address that doesn't answer, or a response's read timeout.
     * Its causes too: an engine wraps it (e.g. Cronet's open in an OpenException).
     */
    private static String describeFailure(IOException ioe) {
        for (Throwable cause = ioe; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return "timed out";
            }

            // The load was cancelled (e.g. by a seek): its thread was interrupted. Cronet's is a class of its own, not java.io's (EY23XNK9
            // 19:07:40, 19:07:54 and WVE7ZKDD 00:30:36: the openings a restore seek cancelled said "failed").
            if (cause instanceof InterruptedIOException || cause instanceof CronetDataSource.InterruptedIOException) {
                return FAILURE_CANCELLED;
            }

            if (cause instanceof NoRouteToHostException) {
                return "unreachable";
            }

            if (cause instanceof ConnectException) {
                String message = cause.getMessage();
                return message != null && message.contains("ENETUNREACH") ? "unreachable" : "refused";
            }

            // E.g. "Trust anchor for certification path not found": something else answered for the server
            if (cause instanceof SSLException) {
                return "TLS failed";
            }
        }

        String message = ioe.getMessage();

        // OkHttp closes the socket of an attempt it cancels ("Socket closed"), or stops one not started yet ("canceled"). Not a
        // handshake's "Connection closed by peer": the server ended that one.
        if ("canceled".equalsIgnoreCase(message)
                || ioe instanceof SocketException && ("Socket closed".equals(message) || "Socket is closed".equals(message))) {
            return FAILURE_CANCELLED;
        }

        return "failed";
    }

    /**
     * The ones between the times, cut to them, as stages of a start's buffering (in the clock's time). One still connecting is
     * what a stuck start waits for, so it's there too.
     */
    static synchronized List<VideoStart.Stage> take(long fromMs, long toMs) {
        List<VideoStart.Stage> result = new ArrayList<>();

        for (Connection connection : sConnections) {
            long startMs = Math.max(connection.startMs, fromMs);
            boolean isGoing = connection.endMs < 0;
            long endMs = isGoing ? toMs : Math.min(connection.endMs, toMs);

            if (endMs - startMs >= 1) {
                result.add(new VideoStart.Stage(VideoStarts.STAGE_CONNECT,
                        connection.describe(isGoing, FormatAttempts.getHostNumber(connection.host)), 1, startMs, endMs));
            }
        }

        addRequests(result, sResponses, VideoStarts.STAGE_RESPONSE, fromMs, toMs);
        addRequests(result, sOpenings, VideoStarts.STAGE_OPENING, fromMs, toMs);

        return result;
    }

    /**
     * The ones that waited long (see MIN_RESPONSE_MS), and the ones that failed: one cancelled isn't (e.g. a seek's), and says so when
     * it waited long
     */
    private static void addRequests(List<VideoStart.Stage> result, ArrayDeque<TimedRequest> requests, String name, long fromMs,
                                    long toMs) {
        for (TimedRequest request : requests) {
            // Made before, it's of the source before (e.g. the video before's)
            if (request.startMs < fromMs) {
                continue;
            }

            boolean isGoing = request.endMs < 0;
            long endMs = isGoing ? toMs : Math.min(request.endMs, toMs);
            boolean isFailed = !isGoing && request.failure != null && !FAILURE_CANCELLED.equals(request.failure);

            if (endMs - request.startMs >= MIN_RESPONSE_MS || isFailed && endMs >= request.startMs) {
                result.add(new VideoStart.Stage(name, describeRequest(request.load, request.timings, isGoing,
                        isGoing ? null : request.failure), 1, request.startMs, endMs));
            }
        }
    }

    /**
     * E.g. "audio init chunk", "audio init chunk, still going", "video chunk, timed out", "video chunk, cancelled", "still going" (its
     * load not known), "audio init chunk, DNS 2 ms, connect 70 ms (TLS 45 ms), h2, response 230 ms" (Cronet's). Null for nothing known.
     *
     * @param timings where its time went (see describeTimings), null for not known
     * @param failure why its answer didn't come, e.g. "timed out", null for one that came or waits
     */
    @Nullable
    private static String describeRequest(@Nullable String load, @Nullable String timings, boolean isGoing, @Nullable String failure) {
        List<String> parts = new ArrayList<>(3);

        if (load != null) {
            parts.add(load);
        }

        if (timings != null) {
            parts.add(timings);
        }

        String state = isGoing ? VideoStarts.DETAIL_GOING : failure;

        if (state != null) {
            parts.add(state);
        }

        return parts.isEmpty() ? null : TextUtils.join(", ", parts);
    }

    /**
     * Which of the player's loads a request is of, e.g. "video init chunk", "audio chunk", "subtitle chunk", "manifest": told by its
     * loader's thread, which it's made on (see Loader#getCurrentLoadable). Null for a request made elsewhere (e.g. on a prefetch's
     * thread) or of another load (e.g. a progressive stream's, whose one request is all of it).
     */
    @VisibleForTesting
    @Nullable
    static String describeLoad(@Nullable Loader.Loadable loadable) {
        if (loadable instanceof Chunk) {
            Chunk chunk = (Chunk) loadable;
            String kind = chunk.type == C.DATA_TYPE_MEDIA_INITIALIZATION ? LOAD_INIT_CHUNK : LOAD_CHUNK;
            String track = trackOf(chunk.trackFormat);

            return track != null ? track + " " + kind : kind;
        }

        return loadable instanceof ParsingLoadable ? LOAD_MANIFEST : null;
    }

    /**
     * "video", "audio", "subtitle", null for one not known (e.g. a muxed stream's)
     */
    @Nullable
    private static String trackOf(@Nullable Format format) {
        if (format == null) {
            return null;
        }

        int trackType = MimeTypes.getTrackType(format.sampleMimeType);

        if (trackType == C.TRACK_TYPE_UNKNOWN) {
            trackType = MimeTypes.getTrackType(format.containerMimeType);
        }

        switch (trackType) {
            case C.TRACK_TYPE_VIDEO:
                return "video";
            case C.TRACK_TYPE_AUDIO:
                return "audio";
            case C.TRACK_TYPE_TEXT:
                return "subtitle";
            default:
                return null;
        }
    }

    /**
     * Also for the error fixer's tests, in another package
     */
    @VisibleForTesting
    public static synchronized void resetForTesting() {
        sPreparedMs = -1;
        sConnections.clear();
        sResponses.clear();
        sOpenings.clear();
        sWallClock = System::currentTimeMillis;
    }
}
