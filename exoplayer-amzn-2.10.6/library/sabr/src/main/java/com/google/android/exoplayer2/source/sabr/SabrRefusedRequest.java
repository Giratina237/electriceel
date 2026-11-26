package com.google.android.exoplayer2.source.sabr;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;

/**
 * The last request SABR's media server refused with HTTP 401 or 403 (see DefaultSabrChunkSource#onChunkLoadError): what it asked for
 * and how old its session was, kept with the error it came as. A SabrProtocolError carries them itself (see
 * SabrProtocolError#setRequest), a response code can't: the app finds them by the error the player fails with. WVE7ZKDD #1, #2: a 403
 * on a rewind 87 s into the session, and whether the seek or a server change drew it couldn't be told.
 */
public final class SabrRefusedRequest {
    @Nullable private static volatile SabrRefusedRequest last;

    private final InvalidResponseCodeException error;
    /**
     * What it asked for (see SabrManifest#describeRequest), null when not known
     */
    @Nullable public final String request;
    /**
     * How long before the session in use had been swapped in (see SabrManifest#getSessionAgeMs), -1 for the stream's first one
     */
    public final long sessionAgeMs;

    private SabrRefusedRequest(InvalidResponseCodeException error, @Nullable String request, long sessionAgeMs) {
        this.error = error;
        this.request = request;
        this.sessionAgeMs = sessionAgeMs;
    }

    /**
     * A load of the track failed: kept when its server refused it
     */
    static void onLoadError(Throwable error, @Nullable String request, long sessionAgeMs) {
        InvalidResponseCodeException refusal = findRefusal(error);

        if (refusal != null) {
            last = new SabrRefusedRequest(refusal, request, sessionAgeMs);
        }
    }

    /**
     * Of the refusal the error is, or came from: null when it's none, or another than the last one kept
     */
    @Nullable
    public static SabrRefusedRequest of(@Nullable Throwable error) {
        SabrRefusedRequest refused = last;

        if (refused == null) {
            return null;
        }

        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause == refused.error) {
                return refused;
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return null;
    }

    @Nullable
    private static InvalidResponseCodeException findRefusal(@Nullable Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidResponseCodeException) {
                int code = ((InvalidResponseCodeException) cause).responseCode;
                return code == 401 || code == 403 ? (InvalidResponseCodeException) cause : null;
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return null;
    }

    /**
     * For the tests: none kept
     */
    public static void resetForTesting() {
        last = null;
    }
}
