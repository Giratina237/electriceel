package com.google.android.exoplayer2.source.sabr.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.BrokenPartError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.BrokenResponseError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.UnknownPartError;
import com.google.android.exoplayer2.source.sabr.parser.misc.SabrExtractorInput;
import com.google.android.exoplayer2.source.sabr.parser.models.AudioSelector;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPart;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPartId;
import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.FormatInitializationMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;

import java.io.ByteArrayOutputStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * A response whose bytes don't read as UMP parts: the errors say where in the response it went wrong (see BrokenResponseError), and a
 * payload that isn't the protobuf its id says is one of them, not a failure of the connection
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SabrStreamBrokenResponseTest {
    private static final int ITAG = 140;
    private static final int UNKNOWN_PART_ID = 120;

    /**
     * The parts read before it, with their sizes, and the bytes the parts were read to: a read that went out of step in the middle
     */
    @Test
    public void anUnknownPartTellsThePartsBeforeIt() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        part(out, UMPPartId.FORMAT_INITIALIZATION_METADATA, formatMetadata());
        part(out, UNKNOWN_PART_ID, new byte[] {1, 2, 3});

        BrokenResponseError error = readFailure(out.toByteArray());

        assertTrue(error instanceof UnknownPartError);
        assertTrue(error.getMessage(), error.getMessage().contains("Unknown part encountered: id=" + UNKNOWN_PART_ID + ", size=3"));
        assertTrue(error.getMessage(), error.getMessage().contains(", after parts " + UMPPartId.FORMAT_INITIALIZATION_METADATA + ":"
                + formatMetadata().length + " " + UNKNOWN_PART_ID + ":3"));
    }

    /**
     * A response that isn't UMP from its first byte (a page, bytes the connection should have decoded) has no part before it
     */
    @Test
    public void aFirstPartThatIsUnknownHasOnlyItselfInTheTrail() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        part(out, UNKNOWN_PART_ID, new byte[] {60, 33, 68});

        BrokenResponseError error = readFailure(out.toByteArray());

        assertTrue(error.getMessage(), error.getMessage().contains(", after parts " + UNKNOWN_PART_ID + ":3"));
    }

    /**
     * The next response's parts start the trail again: the first part of this one isn't told after the last one's
     */
    @Test
    public void theTrailStartsAgainWithAResponse() throws Exception {
        SabrStream sabrStream = newStream();
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        part(first, UMPPartId.FORMAT_INITIALIZATION_METADATA, formatMetadata());
        read(sabrStream, first.toByteArray());

        ByteArrayOutputStream second = new ByteArrayOutputStream();
        part(second, UNKNOWN_PART_ID, new byte[] {9});

        try {
            read(sabrStream, second.toByteArray());
            fail("An unknown part is a broken response");
        } catch (BrokenResponseError error) {
            assertTrue(error.getMessage(), error.getMessage().contains(", after parts " + UNKNOWN_PART_ID + ":1"));
        }
    }

    /**
     * Its id is a known part's and its size is right: the bytes of its payload aren't the message (DMQEWB9B #1: "Protocol message tag
     * had invalid wire type" in a NextRequestPolicy)
     */
    @Test
    public void aPayloadThatIsNotItsMessageIsABrokenPart() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Field 15, wire type 7: no such wire type
        part(out, UMPPartId.NEXT_REQUEST_POLICY, new byte[] {0x7F});

        BrokenResponseError error = readFailure(out.toByteArray());

        assertTrue(error instanceof BrokenPartError);
        assertTrue(error.getMessage(), error.getMessage().contains("InvalidProtocolBufferException"));
        assertTrue(error.getMessage(), error.getMessage().contains(", after parts " + UMPPartId.NEXT_REQUEST_POLICY + ":1"));
    }

    /**
     * A size read out of step is past what an int holds (FVNAAYZD #15: -1721324469): it can't be skipped, which isn't a read that failed
     */
    @Test
    public void aNegativeSizeIsABrokenPart() {
        UMPPart part = new UMPPart(87, -1_721_324_469, new FakeExtractorInput.Builder().setData(new byte[16]).build());

        try {
            part.skip();
            fail("A negative size can't be skipped");
        } catch (BrokenPartError error) {
            assertEquals("Cannot skip part with the id: 87, and size: -1721324469", error.getMessage());
        }
    }

    /**
     * What the chunk source adds: what the response was
     */
    @Test
    public void theResponseIsToldAfterThePartsAndLeavesOutWhatIsNotKnown() {
        BrokenResponseError error = new UnknownPartError("Unknown part encountered: id=120, size=3, position=5");
        error.setPartTrail("120:3");
        error.setResponse("application/vnd.yt-ump", "gzip", 4_096, 1_024);

        assertEquals("Unknown part encountered: id=120, size=3, position=5, after parts 120:3, content type: application/vnd.yt-ump"
                + ", content encoding: gzip, content length: 4096, loaded: 1024", error.getMessage());

        error.setResponse(null, null, -1, -1);

        assertEquals("Unknown part encountered: id=120, size=3, position=5, after parts 120:3", error.getMessage());
    }

    private static BrokenResponseError readFailure(byte[] response) throws Exception {
        try {
            read(newStream(), response);
        } catch (BrokenResponseError error) {
            return error;
        }

        fail("A response that doesn't read as parts is a broken response");

        return null;
    }

    private static byte[] formatMetadata() {
        return FormatInitializationMetadata.newBuilder()
                .setFormatId(FormatId.newBuilder().setItag(ITAG).build())
                .setMimeType("audio/mp4; codecs=\"mp4a.40.2\"")
                .build().toByteArray();
    }

    private static SabrStream newStream() {
        SabrStream sabrStream = new SabrStream(
                "https://rr1---sn-primary.googlevideo.com/videoplayback", "", ClientInfo.getDefaultInstance(),
                1, -1, -1, null, false, "test", C.TIME_UNSET);
        sabrStream.setFormatSelector(new AudioSelector("audio", false, FormatId.newBuilder().setItag(ITAG).build()));

        return sabrStream;
    }

    /**
     * Ids and sizes below 128: a one-byte varint each
     */
    private static void part(ByteArrayOutputStream out, int partId, byte[] payload) throws Exception {
        out.write(partId);
        out.write(payload.length);
        out.write(payload);
    }

    private static void read(SabrStream sabrStream, byte[] response) throws Exception {
        SabrExtractorInput input = new SabrExtractorInput(sabrStream);
        input.init(new FakeExtractorInput.Builder().setData(response).build());
        byte[] buffer = new byte[16];

        while (input.read(buffer, 0, buffer.length) != C.RESULT_END_OF_INPUT) {
            // Read to its end
        }
    }
}
