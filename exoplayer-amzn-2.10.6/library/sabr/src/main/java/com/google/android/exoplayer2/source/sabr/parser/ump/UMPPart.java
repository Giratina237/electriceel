package com.google.android.exoplayer2.source.sabr.parser.ump;

import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.BrokenPartError;

public class UMPPart {
    public final int partId;
    public final int size;
    public final ExtractorInput data;

    public UMPPart(int partId, int size, ExtractorInput data) {
        this.partId = partId;
        this.size = size;
        this.data = data;
    }

    public UMPInputStream toStream() {
        return new UMPInputStream(this);
    }

    public void skip() {
        // A header read out of step: its size is past what an int holds (FVNAAYZD #15: -1721324469). Not a read that failed, which is
        // the connection's (below).
        if (size < 0) {
            throw new BrokenPartError("Cannot skip part with the id: " + partId + ", and size: " + size);
        }

        try {
            data.skipFully(size);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot skip part with the id: " + partId + ", and size: " + size, e);
        }
    }
}
