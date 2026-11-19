package com.google.android.exoplayer2.source.sabr.parser.exceptions;

/**
 * A UMP part whose header was read but whose bytes can't be: a size that can't be skipped (negative, from a header read out of step:
 * FVNAAYZD #15, size -1721324469), or a payload that isn't the protobuf its id says (DMQEWB9B #1: "Protocol message tag had invalid
 * wire type" in a NextRequestPolicy). Not a connection that broke while reading it: that is the data source's own exception, which the
 * loader's wrapper keeps as a cause.
 */
public class BrokenPartError extends BrokenResponseError {
    public BrokenPartError(String msg) {
        super(msg);
    }

    public BrokenPartError(String msg, Throwable cause) {
        super(msg, cause);
    }
}
