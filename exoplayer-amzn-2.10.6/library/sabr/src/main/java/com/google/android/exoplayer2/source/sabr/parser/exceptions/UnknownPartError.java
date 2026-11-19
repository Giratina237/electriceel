package com.google.android.exoplayer2.source.sabr.parser.exceptions;

/**
 * Where a UMP part's header should be, the response has bytes no part starts with (an id far past the known ones): the response is
 * broken or was read out of step, not one the stream can go on with.<br/>
 * Its message has the part's first bytes, and what the response was (see BrokenResponseError#setResponse): a response that isn't UMP
 * at all (e.g. a page) is told from a new part type (T33S5T0B: id 109 at byte 3, its first part).
 */
public class UnknownPartError extends BrokenResponseError {
    public UnknownPartError(String msg) {
        super(msg);
    }
}
