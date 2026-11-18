package com.google.android.exoplayer2.source.sabr.parser.exceptions;

import androidx.annotation.Nullable;

/**
 * Where a UMP part's header should be, the response has bytes no part starts with (an id far past the known ones): the response is
 * broken or was read out of step, not one the stream can go on with. An IllegalStateException, as it was thrown before.<br/>
 * Its message has the part's first bytes, and the response's content type once the chunk source knows it (see #setContentType): a
 * response that isn't UMP at all (e.g. a page) is told from a new part type (T33S5T0B: id 109 at byte 3, its first part).
 */
public class UnknownPartError extends IllegalStateException {
    @Nullable
    private volatile String contentType;

    public UnknownPartError(String msg) {
        super(msg);
    }

    /**
     * Of the response the part was read from (see DefaultSabrChunkSource#onChunkLoadError), before the error goes to the player
     */
    public void setContentType(@Nullable String contentType) {
        this.contentType = contentType;
    }

    @Override
    public String getMessage() {
        String contentType = this.contentType;

        return contentType != null ? super.getMessage() + ", content type: " + contentType : super.getMessage();
    }
}
