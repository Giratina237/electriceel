package com.google.android.exoplayer2.source.sabr.parser;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.source.sabr.parser.misc.SabrExtractorInput;
import com.google.android.exoplayer2.source.sabr.parser.models.AudioSelector;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPartId;
import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.FormatInitializationMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.LiveMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.MediaHeader;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrSeek;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.TimeRange;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;

import java.io.ByteArrayOutputStream;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * A live stream's response: its live metadata and seek parts, and media headers with a start but no duration (seen 2026-11-15)
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SabrStreamLiveTest {
    private static final int ITAG = 140;
    private static final int SEQUENCE = 3_621;
    private static final long START_MS = 18_089_994;
    private static final byte[] MEDIA_DATA = {5, 6, 7, 8, 9, 10};

    /**
     * Its estimate, a tolerance short of the target (see SabrProcessor#processMediaHeader), is kept: the next request asks for the time
     * after the segment. Without it, it asked for the segment's start, and the server answered with the same segment's start again.
     */
    @Test
    public void aLiveSegmentWithoutDurationIsGivenTheTargets() throws Exception {
        SabrStream sabrStream = newStream(2);
        sabrStream.setLive(true);

        assertArrayEquals(MEDIA_DATA, readAll(sabrStream, liveResponse(true)));

        assertEquals(1_900, sabrStream.getSegmentDurationMs(ITAG));
        assertEquals(START_MS + 1_900, sabrStream.getSegmentStartTimeMs(ITAG));
        assertEquals(SEQUENCE, sabrStream.getInitializedFormat(ITAG).getSequenceNumber());
    }

    /**
     * Skipped as unknown before 2026-11-15: the segment had no duration (Cannot determine duration of segment), and the stream failed
     */
    @Test
    public void theLiveMetadataMakesTheStreamLive() throws Exception {
        SabrStream sabrStream = newStream(5);

        assertArrayEquals(MEDIA_DATA, readAll(sabrStream, liveResponse(true)));

        assertTrue(sabrStream.isLive());
        assertEquals(4_900, sabrStream.getSegmentDurationMs(ITAG));
    }

    @Test
    public void aLiveStreamWithoutLiveMetadataYetIsLiveByItsFormatInfo() throws Exception {
        SabrStream sabrStream = newStream(1);
        sabrStream.setLive(true);

        assertArrayEquals(MEDIA_DATA, readAll(sabrStream, liveResponse(false)));

        assertEquals(900, sabrStream.getSegmentDurationMs(ITAG));
    }

    private static SabrStream newStream(int targetDurationSec) {
        SabrStream sabrStream = new SabrStream(
                "https://rr1---sn-primary.googlevideo.com/videoplayback", "", ClientInfo.getDefaultInstance(),
                targetDurationSec, -1, -1, null, false, "test", C.TIME_UNSET);
        sabrStream.setFormatSelector(new AudioSelector("audio", false, FormatId.newBuilder().setItag(ITAG).build()));
        return sabrStream;
    }

    /**
     * As the server sends it for a live stream: the format's metadata, the live metadata, a seek to the head, then a segment whose
     * header has a start (in ms and in ticks) but no duration
     */
    private static byte[] liveResponse(boolean withLiveMetadata) throws Exception {
        FormatId formatId = FormatId.newBuilder().setItag(ITAG).build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        part(out, UMPPartId.FORMAT_INITIALIZATION_METADATA, FormatInitializationMetadata.newBuilder()
                .setFormatId(formatId)
                .setMimeType("audio/mp4; codecs=\"mp4a.40.2\"")
                .build().toByteArray());

        if (withLiveMetadata) {
            part(out, UMPPartId.LIVE_METADATA, LiveMetadata.newBuilder()
                    .setHeadSequenceNumber(SEQUENCE + 2)
                    .setHeadSequenceTimeMs(START_MS + 10_000)
                    .setMaxSeekableTimeTicks(START_MS * 1_000)
                    .setMaxSeekableTimescale(1_000_000)
                    .build().toByteArray());
            part(out, UMPPartId.SABR_SEEK, SabrSeek.newBuilder()
                    .setSeekMediaTime(START_MS * 44_100 / 1_000)
                    .setSeekMediaTimescale(44_100)
                    .build().toByteArray());
        }

        part(out, UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder()
                .setHeaderId(0)
                .setItag(ITAG)
                .setFormatId(formatId)
                .setSequenceNumber(SEQUENCE)
                .setStartMs(START_MS)
                .setTimeRange(TimeRange.newBuilder().setStartTicks(START_MS * 44_100 / 1_000).setTimescale(44_100).build())
                .build().toByteArray());
        byte[] media = new byte[1 + MEDIA_DATA.length];
        System.arraycopy(MEDIA_DATA, 0, media, 1, MEDIA_DATA.length);
        part(out, UMPPartId.MEDIA, media);
        part(out, UMPPartId.MEDIA_END, new byte[] {0});
        return out.toByteArray();
    }

    /**
     * Ids and sizes below 128: a one-byte varint each
     */
    private static void part(ByteArrayOutputStream out, int partId, byte[] payload) throws Exception {
        out.write(partId);
        out.write(payload.length);
        out.write(payload);
    }

    private static byte[] readAll(SabrStream sabrStream, byte[] response) throws Exception {
        SabrExtractorInput input = new SabrExtractorInput(sabrStream);
        input.init(new FakeExtractorInput.Builder().setData(response).build());
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[16];
        int read;

        while ((read = input.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
            result.write(buffer, 0, read);
        }

        return result.toByteArray();
    }
}
