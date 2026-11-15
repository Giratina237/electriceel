package com.google.android.exoplayer2.source.sabr.parser.adapter;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.models.VideoSelector;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPartId;
import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.FormatInitializationMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.MediaHeader;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.TimeRange;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;
import com.google.android.exoplayer2.testutil.FakeExtractorOutput;
import com.google.android.exoplayer2.testutil.FakeTrackOutput;

import java.io.ByteArrayOutputStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * A live WebM segment is a whole WebM file: its own EBML header and Segment. After a start or a seek the server answers with several:
 * read on from the first's end, the second Segment was refused (Multiple Segment elements not supported), the chunk ended with the first
 * segment, and the rest of the response was thrown away, then asked for again (~3 MB per response for a 4K stream, 2026-11-15).
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SabrMatroskaAdapterLiveTest {
    private static final int ITAG = 248;
    private static final int SEQUENCE = 466_988;
    private static final long START_MS = 18_089_000;
    private static final int TRACK_NUMBER = 1;

    @Test
    public void readsEachLiveSegmentOfAResponse() throws Exception {
        SabrStream sabrStream = newStream();
        sabrStream.setLive(true);
        FakeExtractorOutput output = new FakeExtractorOutput();
        SabrMatroskaAdapter adapter = new SabrMatroskaAdapter(sabrStream);
        adapter.init(output);

        readAll(adapter, response(webm(1_000, new byte[] {1, 2, 3, 4}), webm(2_000, new byte[] {5, 6, 7, 8})));

        FakeTrackOutput track = output.trackOutputs.get(TRACK_NUMBER);
        assertEquals(2, track.getSampleCount());
        assertEquals(1_000_000, track.getSampleTimeUs(0));
        assertEquals(2_000_000, track.getSampleTimeUs(1));
    }

    private static SabrStream newStream() {
        SabrStream sabrStream = new SabrStream(
                "https://rr1---sn-primary.googlevideo.com/videoplayback", "", ClientInfo.getDefaultInstance(),
                1, -1, -1, null, false, "test", 60_000);
        sabrStream.setFormatSelector(new VideoSelector("video", false, FormatId.newBuilder().setItag(ITAG).build()));
        return sabrStream;
    }

    private static void readAll(SabrMatroskaAdapter adapter, byte[] response) throws Exception {
        FakeExtractorInput input = new FakeExtractorInput.Builder().setData(response).build();
        PositionHolder positionHolder = new PositionHolder();
        int result;

        do {
            result = adapter.read(input, positionHolder);
        } while (result == Extractor.RESULT_CONTINUE);

        assertEquals(Extractor.RESULT_END_OF_INPUT, result);
    }

    /**
     * As the server sends two live segments: the format's metadata, then a header, the data and the end of each
     */
    private static byte[] response(byte[] first, byte[] second) throws Exception {
        FormatId formatId = FormatId.newBuilder().setItag(ITAG).build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        part(out, UMPPartId.FORMAT_INITIALIZATION_METADATA, FormatInitializationMetadata.newBuilder()
                .setFormatId(formatId)
                .setMimeType("video/webm; codecs=\"vp9\"")
                .build().toByteArray());
        segment(out, formatId, 0, SEQUENCE, START_MS, first);
        segment(out, formatId, 1, SEQUENCE + 1, START_MS + 1_000, second);
        return out.toByteArray();
    }

    private static void segment(ByteArrayOutputStream out, FormatId formatId, int headerId, int sequence, long startMs, byte[] data)
            throws Exception {
        part(out, UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder()
                .setHeaderId(headerId)
                .setItag(ITAG)
                .setFormatId(formatId)
                .setSequenceNumber(sequence)
                .setStartMs(startMs)
                .setTimeRange(TimeRange.newBuilder().setStartTicks(startMs).setTimescale(1_000).build())
                .build().toByteArray());
        byte[] media = new byte[1 + data.length];
        media[0] = (byte) headerId;
        System.arraycopy(data, 0, media, 1, data.length);
        part(out, UMPPartId.MEDIA, media);
        part(out, UMPPartId.MEDIA_END, new byte[] {(byte) headerId});
    }

    /**
     * A whole WebM file, as a live segment is: EBML header, then a Segment with its info, a VP9 track and a cluster of one keyframe
     */
    private static byte[] webm(int clusterTimecodeMs, byte[] frame) throws Exception {
        byte[] ebml = element(0x1A45DFA3, element(0x4282, "webm".getBytes("US-ASCII")));
        byte[] info = element(0x1549A966, element(0x2AD7B1, new byte[] {0x0F, 0x42, 0x40})); // timecode scale: 1 ms
        byte[] video = element(0xE0, concat(element(0xB0, new byte[] {0x05, 0x00}), element(0xBA, new byte[] {0x02, (byte) 0xD0})));
        byte[] trackEntry = element(0xAE, concat(
                element(0xD7, new byte[] {TRACK_NUMBER}),
                element(0x83, new byte[] {1}), // video
                element(0x86, "V_VP9".getBytes("US-ASCII")),
                video));
        byte[] tracks = element(0x1654AE6B, trackEntry);
        byte[] simpleBlock = concat(new byte[] {(byte) (0x80 | TRACK_NUMBER), 0, 0, (byte) 0x80}, frame); // track, timecode 0, keyframe
        byte[] cluster = element(0x1F43B675, concat(
                element(0xE7, new byte[] {(byte) (clusterTimecodeMs >> 8), (byte) clusterTimecodeMs}),
                element(0xA3, simpleBlock)));
        return concat(ebml, element(0x18538067, concat(info, tracks, cluster)));
    }

    /**
     * Its id's bytes as written (the id keeps its length marker), and a one-byte size
     */
    private static byte[] element(int id, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int idLength = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;

        for (int i = idLength - 1; i >= 0; i--) {
            out.write(id >> (8 * i));
        }

        if (content.length >= 0x7F) {
            throw new IllegalArgumentException("One-byte sizes only: " + content.length);
        }

        out.write(0x80 | content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }

        return out.toByteArray();
    }

    /**
     * Ids and sizes below 128: a one-byte varint each
     */
    private static void part(ByteArrayOutputStream out, int partId, byte[] payload) {
        if (payload.length >= 128) {
            throw new IllegalArgumentException("One-byte sizes only: " + payload.length);
        }

        out.write(partId);
        out.write(payload.length);
        out.write(payload, 0, payload.length);
    }
}
