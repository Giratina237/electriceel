package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.liskovsoft.googlecommon.common.helpers.ConnectionErrors;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's loads that failed (see AnalyticsListener#onLoadError), whatever its engine. Shown in a start's buffering (see
 * VideoStarts): a failed load and the player's wait before it tried again had no row (S4PGCB6X: 4.4 s with none between the init chunks
 * and the first server backoff). Asked whether it's the connection that fails (see ErrorFixerController#runLongBufferingAction): another
 * client streams from the same servers. Told and read on the main thread.
 */
public final class LoadErrors {
    /**
     * The last ones: a start only needs the ones since it was prepared
     */
    static final int MAX_ERRORS = 8;
    private static final ArrayDeque<LoadError> sErrors = new ArrayDeque<>();
    @VisibleForTesting
    static VideoStarts.Clock sClock = SystemClock::elapsedRealtime;
    /**
     * When the player was last prepared with a source, in the clock's time, -1 for not yet: one failed before is of the source before
     * (e.g. the video before's)
     */
    private static long sPreparedMs = -1;

    private static final class LoadError {
        final long startMs;
        final long endMs;
        @Nullable
        final String load;
        final String error;
        final boolean isConnection;

        LoadError(long startMs, long endMs, @Nullable String load, String error, boolean isConnection) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.load = load;
            this.error = error;
            this.isConnection = isConnection;
        }
    }

    private LoadErrors() {
    }

    /**
     * A load failed, in the clock's time. The player tries it again, a while later, or gives up with an error of its own.
     *
     * @param dataType of the load, e.g. C#DATA_TYPE_MEDIA_INITIALIZATION
     * @param trackType of the load, e.g. C#TRACK_TYPE_AUDIO
     */
    public static synchronized void onLoadError(long startMs, long endMs, int dataType, int trackType, IOException error) {
        sErrors.addLast(new LoadError(startMs, endMs, describeLoad(dataType, trackType), describeError(error), isConnectionError(error)));

        if (sErrors.size() > MAX_ERRORS) {
            sErrors.removeFirst();
        }
    }

    /**
     * The player was prepared with a source (e.g. another video, or the same one again by a reload)
     */
    public static synchronized void onPrepare() {
        sPreparedMs = sClock.now();
    }

    /**
     * A load of the source the player has failed on its connection this recently: it didn't connect, its name didn't resolve, or its
     * answer didn't come
     */
    public static synchronized boolean hasConnectionErrorWithin(long windowMs) {
        long sinceMs = Math.max(sClock.now() - windowMs, sPreparedMs);

        for (LoadError error : sErrors) {
            if (error.isConnection && error.endMs >= sinceMs) {
                return true;
            }
        }

        return false;
    }

    /**
     * The ones that ended between the times, cut to them, as stages of a start's buffering (in the clock's time): e.g. "video chunk,
     * timed out". One that failed at once is a moment.
     */
    static synchronized List<VideoStart.Stage> take(long fromMs, long toMs) {
        List<VideoStart.Stage> result = new ArrayList<>();

        for (LoadError error : sErrors) {
            if (error.endMs < fromMs || error.endMs > toMs) {
                continue;
            }

            String detail = error.load != null ? error.load + ", " + error.error : error.error;
            result.add(new VideoStart.Stage(VideoStarts.STAGE_LOAD_ERROR, detail, 1, Math.max(error.startMs, fromMs), error.endMs));
        }

        return result;
    }

    /**
     * The connection to the server failed, or the answer on it didn't come: the network's or the server's, not what was asked for. Its
     * causes too: the player wraps it (e.g. in an HttpDataSourceException, SABR's in an IllegalStateException). See ConnectionErrors,
     * which the client attempts of a fetch are told by too.
     */
    public static boolean isConnectionError(@Nullable Throwable error) {
        return ConnectionErrors.isConnectionError(error);
    }

    /**
     * E.g. "timed out", "response code 403", else the name of its last cause, e.g. "SabrReloadPlayerError"
     */
    @VisibleForTesting
    static String describeError(Throwable error) {
        Throwable last = error;

        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidResponseCodeException) {
                return "response code " + ((InvalidResponseCodeException) cause).responseCode;
            }

            last = cause;

            if (cause.getCause() == cause) {
                break;
            }
        }

        String connection = ConnectionErrors.describe(error);

        return connection != null ? connection : last.getClass().getSimpleName();
    }

    /**
     * E.g. "video init chunk", "audio chunk", "manifest" (see Connections#describeLoad), null for one not known
     */
    @Nullable
    private static String describeLoad(int dataType, int trackType) {
        String kind;

        switch (dataType) {
            case C.DATA_TYPE_MEDIA_INITIALIZATION:
                kind = Connections.LOAD_INIT_CHUNK;
                break;
            case C.DATA_TYPE_MEDIA:
                kind = Connections.LOAD_CHUNK;
                break;
            case C.DATA_TYPE_MANIFEST:
                return Connections.LOAD_MANIFEST;
            default:
                return null;
        }

        String track;

        switch (trackType) {
            case C.TRACK_TYPE_VIDEO:
                track = "video";
                break;
            case C.TRACK_TYPE_AUDIO:
                track = "audio";
                break;
            case C.TRACK_TYPE_TEXT:
                track = "subtitle";
                break;
            default:
                track = null;
                break;
        }

        return track != null ? track + " " + kind : kind;
    }

    /**
     * For the error fixer's tests, in another package
     */
    @VisibleForTesting
    public static void resetForTesting() {
        resetForTesting(null);
    }

    @VisibleForTesting
    static synchronized void resetForTesting(@Nullable VideoStarts.Clock clock) {
        sErrors.clear();
        sPreparedMs = -1;
        sClock = clock != null ? clock : SystemClock::elapsedRealtime;
    }
}
