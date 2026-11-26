package com.google.android.exoplayer2.source.sabr.parser.exceptions;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrError;

/**
 * The server answered the request with a SabrError part instead of media, e.g. sabr.malformed_config (the ustreamer config of the
 * player response no longer applies, and every request after it is refused) or sabr.no_available_formats. The same request gets
 * the same answer: the player response is fetched again, or another client's.
 */
public class SabrProtocolError extends SabrStreamError {
    public static final int UNSET = -1;
    /**
     * E.g. sabr.malformed_config, null when the server sent none
     */
    @Nullable
    public final String type;
    /**
     * The action the server asks for (2 in every report so far, of sabr.malformed_config and sabr.no_available_formats). No client
     * documents its values. UNSET when the server sent none.
     */
    public final int action;
    /**
     * The HTTP status the server gives the error, UNSET when it gave none
     */
    public final int statusCode;
    /**
     * What the refused request asked for (see SabrManifest#describeRequest), null when not known
     */
    @Nullable
    private volatile String request;
    /**
     * How long before the refusal the session it was made with was swapped in (see SabrManifest#requestSessionRefresh), UNSET for the
     * stream's first one
     */
    private volatile long sessionAgeMs = UNSET;

    public SabrProtocolError(SabrError sabrError) {
        this(sabrError.hasType() ? sabrError.getType() : null,
                sabrError.hasAction() ? sabrError.getAction() : UNSET,
                sabrError.hasError() && sabrError.getError().hasStatusCode() ? sabrError.getError().getStatusCode() : UNSET);
    }

    public SabrProtocolError(@Nullable String type, int action, int statusCode) {
        super(getMessage(type, action, statusCode));
        this.type = type;
        this.action = action;
        this.statusCode = statusCode;
    }

    /**
     * Told by the chunk source that made the request (see DefaultSabrChunkSource#onChunkLoadError): the error is parsed from the
     * response alone
     *
     * @param sessionAgeMs see {@link #getSessionAgeMs()}
     */
    public void setRequest(@Nullable String request, long sessionAgeMs) {
        this.request = request;
        this.sessionAgeMs = sessionAgeMs;
    }

    /**
     * E.g. "init, video 248:xtags, audio 251, selected none, session 2", null when not known
     */
    @Nullable
    public String getRequest() {
        return request;
    }

    /**
     * How long before it the stream's session was refreshed (the player response fetched again, see SabrManifest#refreshSession),
     * UNSET for the session the stream started with. A refused fresh one says the client is refused, not a stale player response.
     */
    public long getSessionAgeMs() {
        return sessionAgeMs;
    }

    /**
     * One line, for the player's message and the diagnostics, e.g. "SABR Protocol Error: sabr.malformed_config, action 2"
     */
    private static String getMessage(@Nullable String type, int action, int statusCode) {
        StringBuilder message = new StringBuilder("SABR Protocol Error: ").append(type != null ? type : "unknown");

        if (action != UNSET) {
            message.append(", action ").append(action);
        }

        if (statusCode != UNSET) {
            message.append(", status ").append(statusCode);
        }

        return message.toString();
    }
}
