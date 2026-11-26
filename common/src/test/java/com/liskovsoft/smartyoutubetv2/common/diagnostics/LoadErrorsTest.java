package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.Loader.UnexpectedLoaderException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LoadErrorsTest {
    private final AtomicLong mNow = new AtomicLong(100_000);

    @Before
    public void setUp() {
        LoadErrors.resetForTesting(mNow::get);
    }

    @After
    public void tearDown() {
        LoadErrors.resetForTesting(null);
    }

    /**
     * Its load and why: the wait before the player tries again is what follows it
     */
    @Test
    public void aLoadErrorIsAStageOfTheBuffering() {
        LoadErrors.onLoadError(1_000, 5_000, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException("timeout"));
        LoadErrors.onLoadError(6_000, 6_000, C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_AUDIO,
                new InvalidResponseCodeException(403, null, Collections.emptyMap(), dataSpec()));

        List<VideoStart.Stage> stages = LoadErrors.take(500, 10_000);

        assertEquals(2, stages.size());
        assertEquals(VideoStarts.STAGE_LOAD_ERROR, stages.get(0).name);
        assertEquals("video chunk, timed out", stages.get(0).detail);
        assertEquals(1_000, stages.get(0).startMs);
        assertEquals(5_000, stages.get(0).endMs);
        assertEquals("audio init chunk, response code 403", stages.get(1).detail);
        assertEquals(0, stages.get(1).getDurationMs());
    }

    /**
     * Ended before the start, it's the source before's: one that began before it is cut to it
     */
    @Test
    public void onlyTheOnesOfTheStartCutToIt() {
        LoadErrors.onLoadError(100, 400, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException());
        LoadErrors.onLoadError(300, 900, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, new SocketTimeoutException());

        List<VideoStart.Stage> stages = LoadErrors.take(500, 10_000);

        assertEquals(1, stages.size());
        assertEquals(500, stages.get(0).startMs);
        assertEquals(900, stages.get(0).endMs);
    }

    @Test
    public void onlyTheLastOnesAreKept() {
        for (int i = 0; i < LoadErrors.MAX_ERRORS + 3; i++) {
            LoadErrors.onLoadError(1_000 + i, 1_000 + i, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException());
        }

        assertEquals(LoadErrors.MAX_ERRORS, LoadErrors.take(0, 10_000).size());
    }

    /**
     * Wrapped as the player gets them: SABR's in an IllegalStateException, the data source's in an HttpDataSourceException
     */
    @Test
    public void aConnectionsFailureIsToldThroughItsWrappers() {
        assertTrue(LoadErrors.isConnectionError(new UnexpectedLoaderException(new IllegalStateException(new HttpDataSourceException(
                new SocketTimeoutException("timeout"), dataSpec(), HttpDataSourceException.TYPE_READ)))));
        assertTrue(LoadErrors.isConnectionError(new IllegalStateException(new UnknownHostException("www.youtube.com"))));
        assertTrue(LoadErrors.isConnectionError(new IllegalStateException(
                new SSLHandshakeException("Trust anchor for certification path not found."))));
        assertTrue(LoadErrors.isConnectionError(new ConnectException("failed to connect")));
    }

    /**
     * The server's answer, or what was in it: not the connection's
     */
    @Test
    public void anAnswerIsntTheConnections() {
        assertFalse(LoadErrors.isConnectionError(new InvalidResponseCodeException(403, null, Collections.emptyMap(), dataSpec())));
        assertFalse(LoadErrors.isConnectionError(new UnexpectedLoaderException(new IllegalStateException("Unknown part"))));
        assertFalse(LoadErrors.isConnectionError(new IOException("Response code: 403")));
        assertFalse(LoadErrors.isConnectionError(null));
    }

    @Test
    public void anErrorIsToldShort() {
        assertEquals("timed out", LoadErrors.describeError(new IOException(new SocketTimeoutException())));
        assertEquals("name not resolved", LoadErrors.describeError(new IOException(new UnknownHostException())));
        assertEquals("refused", LoadErrors.describeError(new ConnectException("ECONNREFUSED")));
        assertEquals("unreachable", LoadErrors.describeError(new ConnectException("ENETUNREACH (Network is unreachable)")));
        assertEquals("TLS failed", LoadErrors.describeError(new SSLHandshakeException("bad")));
        assertEquals("IllegalStateException", LoadErrors.describeError(new UnexpectedLoaderException(new IllegalStateException("x"))));
    }

    /**
     * The connection is what fails a hang only when a load failed on it lately
     */
    @Test
    public void aConnectionErrorIsRecentForAWhile() {
        LoadErrors.onLoadError(90_000, 95_000, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException());
        LoadErrors.onLoadError(98_000, 99_000, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO,
                new InvalidResponseCodeException(403, null, Collections.emptyMap(), dataSpec()));

        assertTrue(LoadErrors.hasConnectionErrorWithin(10_000));
        assertFalse(LoadErrors.hasConnectionErrorWithin(4_000));
    }

    /**
     * The same video prepared again (a reload, e.g. the error fixer's after a stall): the loads that failed before it are its
     * connection's still. Another video's start over.
     */
    @Test
    public void aReloadOfTheVideoGoesOnWithTheErrorsBeforeIt() {
        mNow.set(90_000);
        LoadErrors.onPrepare("video");
        LoadErrors.onLoadError(95_000, 99_000, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException());
        mNow.set(101_000);
        LoadErrors.onPrepare("video");

        assertTrue(LoadErrors.hasConnectionErrorWithin(30_000));

        LoadErrors.onPrepare("other");

        assertFalse(LoadErrors.hasConnectionErrorWithin(30_000));
    }

    /**
     * Prepared with a video not known: another one's, as before
     */
    @Test
    public void aPrepareWithNoVideoStartsOver() {
        mNow.set(90_000);
        LoadErrors.onPrepare("video");
        LoadErrors.onLoadError(95_000, 99_000, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException());
        mNow.set(101_000);
        LoadErrors.onPrepare();

        assertFalse(LoadErrors.hasConnectionErrorWithin(30_000));

        // And the one after it isn't the same video again
        LoadErrors.onLoadError(101_500, 102_000, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException());
        mNow.set(103_000);
        LoadErrors.onPrepare("video");

        assertFalse(LoadErrors.hasConnectionErrorWithin(30_000));
    }

    /**
     * R71TW3F4 #1-#13: a source failure tells the tries of the loads that failed since it last played, each with how long it took and why.
     * Before that, they're another time's.
     */
    @Test
    public void aSourceFailureTellsTheLoadsThatFailedSinceItPlayed() throws Exception {
        mNow.set(90_000);
        LoadErrors.onPrepare("zb6EkKExF_8");
        LoadErrors.onLoadError(91_000, 91_500, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, new SocketTimeoutException());
        mNow.set(92_000);
        LoadErrors.onPlay();
        LoadErrors.onLoadError(120_000, 124_000, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException("timeout"));
        LoadErrors.onLoadError(125_000, 129_010, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO, new SocketTimeoutException("timeout"));
        mNow.set(130_000);

        org.json.JSONArray errors = LoadErrors.describeSincePlay();

        assertEquals(2, errors.length());
        org.json.JSONObject first = errors.getJSONObject(0);
        assertEquals("video chunk", first.getString("load"));
        assertEquals("timed out", first.getString("error"));
        assertEquals(4_000, first.getLong("ms"));
        assertEquals(6_000, first.getLong("endAgoMs"));
        assertEquals(4_010, errors.getJSONObject(1).getLong("ms"));
    }

    /**
     * None since: nothing to tell
     */
    @Test
    public void noLoadFailedSinceItPlayedTellsNone() {
        LoadErrors.onLoadError(91_000, 91_500, C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO, new SocketTimeoutException());
        mNow.set(95_000);
        LoadErrors.onPlay();

        assertEquals(null, LoadErrors.describeSincePlay());
    }

    private static DataSpec dataSpec() {
        return new DataSpec(Uri.parse("https://rr1---sn-test.googlevideo.com/videoplayback"));
    }
}
