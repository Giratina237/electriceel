package com.google.android.exoplayer2.source.sabr.parser.exceptions;

import androidx.annotation.Nullable;

/**
 * The bytes of a SABR response don't read as what they should: a part whose id or size no part has, or one that isn't the protobuf
 * its id says. The response is broken or was read out of step, not a connection that failed (that is the data source's own exception
 * under the loader's) or a server that said no. An IllegalStateException, as these were thrown before.<br/>
 * Its message tells what the response was (see #setResponse) and the parts read before it (see #setPartTrail), so a response that
 * isn't UMP at all (e.g. a page, or bytes the connection should have decoded) is told from one that went out of step in the middle,
 * and an engine's from another's.
 */
public class BrokenResponseError extends IllegalStateException {
    @Nullable
    private volatile String response;
    @Nullable
    private volatile String partTrail;

    public BrokenResponseError(String msg) {
        super(msg);
    }

    public BrokenResponseError(String msg, Throwable cause) {
        super(msg, cause);
    }

    /**
     * Of the response the part was read from (see DefaultSabrChunkSource#onChunkLoadError), before the error goes to the player
     *
     * @param contentType     e.g. "application/vnd.yt-ump", null when the response had none
     * @param contentEncoding e.g. "gzip", null when it had none
     * @param contentLength   -1 when not known
     * @param bytesLoaded     what the data source delivered of the response, against what the parts read of it (their position)
     */
    public void setResponse(@Nullable String contentType, @Nullable String contentEncoding, long contentLength, long bytesLoaded) {
        StringBuilder text = new StringBuilder();

        if (contentType != null) {
            text.append(", content type: ").append(contentType);
        }

        if (contentEncoding != null) {
            text.append(", content encoding: ").append(contentEncoding);
        }

        if (contentLength >= 0) {
            text.append(", content length: ").append(contentLength);
        }

        if (bytesLoaded >= 0) {
            text.append(", loaded: ").append(bytesLoaded);
        }

        response = text.toString();
    }

    /**
     * The parts read before it, oldest first, as "id:size", e.g. "35:12 20:1500 21:13": see SabrStream#getPartTrail
     */
    public void setPartTrail(@Nullable String partTrail) {
        this.partTrail = partTrail;
    }

    @Override
    public String getMessage() {
        String message = super.getMessage();
        String partTrail = this.partTrail;
        String response = this.response;

        if (partTrail != null && !partTrail.isEmpty()) {
            message += ", after parts " + partTrail;
        }

        return response != null ? message + response : message;
    }
}
