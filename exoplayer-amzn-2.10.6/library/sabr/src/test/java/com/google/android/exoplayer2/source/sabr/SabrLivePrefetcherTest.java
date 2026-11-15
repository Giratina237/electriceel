package com.google.android.exoplayer2.source.sabr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.ChunkHolder;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.sabr.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.sabr.manifest.Period;
import com.google.android.exoplayer2.source.sabr.manifest.Representation;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.manifest.SegmentBase.SegmentTemplate;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.misc.SabrExtractorInput;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPartId;
import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.FormatInitializationMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.LiveMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.MediaHeader;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.google.android.exoplayer2.testutil.FakeExtractorInput;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * A live stream with 1 s segments: an edge answers a request for a segment it hasn't cached in about a second, so the next segments are
 * asked for while the player loads one, and their responses are what the player's requests get
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SabrLivePrefetcherTest {
    private static final String SABR_URL = "https://rr1---sn-primary.googlevideo.com/videoplayback?source=yt_live_broadcast";
    private static final int ITAG = 140;
    private static final int SEQUENCE = 423_104;
    private static final long START_MS = 423_098_967;
    private static final byte[] RESPONSE = {1, 2, 3, 4, 5};

    @Test
    public void thePlayersRequestGetsTheResponseFetchedAhead() throws Exception {
        RecordingFactory factory = new RecordingFactory(RESPONSE);
        SabrLivePrefetcher prefetcher = new SabrLivePrefetcher(factory, null);
        RecordingDataSource upstream = new RecordingDataSource(new byte[] {9});

        prefetcher.prefetch(ITAG, SEQUENCE, spec(), 1_000);
        DataSource dataSource = prefetcher.dataSourceFor(ITAG, SEQUENCE, upstream);

        assertArrayEquals(RESPONSE, readAll(dataSource));
        assertEquals(0, upstream.opened);
        assertEquals(1, factory.sources.size());
    }

    @Test
    public void aFailedFetchIsAskedAgain() throws Exception {
        SabrLivePrefetcher prefetcher = new SabrLivePrefetcher(new RecordingFactory(null), null);
        RecordingDataSource upstream = new RecordingDataSource(new byte[] {9});

        prefetcher.prefetch(ITAG, SEQUENCE, spec(), 1_000);

        assertArrayEquals(new byte[] {9}, readAll(prefetcher.dataSourceFor(ITAG, SEQUENCE, upstream)));
        assertEquals(1, upstream.opened);
    }

    /**
     * At the start of a stream the server may answer with a burst toward its 15 s readahead (over 8 MB for a 2 s segment of 1440p): it
     * isn't kept, the player asks itself, and nothing more is asked for ahead for a while
     */
    @Test
    public void aBurstIsntKeptAndPausesThePrefetch() throws Exception {
        RecordingFactory factory = new RecordingFactory(new byte[2_000]);
        SabrLivePrefetcher prefetcher = new SabrLivePrefetcher(factory, null);
        RecordingDataSource upstream = new RecordingDataSource(new byte[] {9});

        prefetcher.prefetch(ITAG, SEQUENCE, spec(), 1_000);
        assertArrayEquals(new byte[] {9}, readAll(prefetcher.dataSourceFor(ITAG, SEQUENCE, upstream)));
        prefetcher.prefetch(ITAG, SEQUENCE + 1, spec(), 1_000);

        assertEquals(1, upstream.opened);
        assertEquals(1, factory.sources.size());
    }

    @Test
    public void aResponseIsKeptUpToAFewSegmentsOfTheFormat() {
        assertEquals(1024 * 1024, SabrLivePrefetcher.getMaxResponseBytes(144_000, 1_000, 1)); // audio: at least 1 MB
        assertEquals(5_625_000, SabrLivePrefetcher.getMaxResponseBytes(9_000_000, 2_000, 1)); // 1440p: 2.5 segments
        assertEquals(12 * 1024 * 1024, SabrLivePrefetcher.getMaxResponseBytes(25_000_000, 2_000, 1)); // 4K: at most 12 MB
        assertEquals(2_531_250, SabrLivePrefetcher.getMaxResponseBytes(4_500_000, 1_000, 3)); // 3 segments of 1080p: 4.5 segments
    }

    /**
     * A response that brought several segments (audio's do): the ones asked for ahead before the next one asked for won't be read
     */
    @Test
    public void earlierSegmentsAreDroppedAndAnotherFormatIsntServed() throws Exception {
        RecordingFactory factory = new RecordingFactory(RESPONSE);
        SabrLivePrefetcher prefetcher = new SabrLivePrefetcher(factory, null);
        RecordingDataSource upstream = new RecordingDataSource(new byte[] {9});
        prefetcher.prefetch(ITAG, SEQUENCE, spec(), 1_000);
        prefetcher.prefetch(ITAG, SEQUENCE + 1, spec(), 1_000);
        prefetcher.prefetch(ITAG, SEQUENCE + 2, spec(), 1_000);

        assertSame(upstream, prefetcher.dataSourceFor(251, SEQUENCE + 2, upstream));
        assertTrue(prefetcher.dataSourceFor(ITAG, SEQUENCE + 2, upstream) instanceof SabrLivePrefetcher.PrefetchedDataSource);
        assertSame(upstream, prefetcher.dataSourceFor(ITAG, SEQUENCE + 1, upstream));
        assertSame(upstream, prefetcher.dataSourceFor(ITAG, SEQUENCE, upstream));
    }

    /**
     * After a segment arrived: the player's next request is its own, the three after it are asked for ahead, as if each one before them
     * were the newest received (by number); none past the head, which the server would hold until it exists
     */
    @Test
    public void theNextSegmentsUpToTheHeadAreAskedForAhead() throws Exception {
        RecordingFactory factory = new RecordingFactory(RESPONSE);
        SabrManifest manifest = liveManifest(1);
        DefaultSabrChunkSource source = source(manifest);
        source.enableLivePrefetch(factory, null);
        MediaChunk previous = firstChunk(source); // a seek: the stream is reset
        receive(manifest.getSabrStream(C.TRACK_TYPE_AUDIO), SEQUENCE + 3);
        ChunkHolder out = new ChunkHolder();

        source.getNextChunk(0, 0, Collections.singletonList(previous), out);

        assertEquals(2, factory.sources.size()); // SEQUENCE + 2 and + 3: + 4 is past the head
        VideoPlaybackAbrRequest first = VideoPlaybackAbrRequest.parseFrom(opened(factory.sources.get(0)).httpBody);
        assertEquals(SEQUENCE + 1, first.getBufferedRanges(0).getStartSegmentIndex());
        assertEquals(START_MS + 1_000, first.getBufferedRanges(0).getStartTimeMs());
        VideoPlaybackAbrRequest second = VideoPlaybackAbrRequest.parseFrom(opened(factory.sources.get(1)).httpBody);
        assertEquals(SEQUENCE + 2, second.getBufferedRanges(0).getStartSegmentIndex());
    }

    /**
     * Behind the head, a response holds the segments after the one asked for too (3 of a 1 s stream): the ones asked for ahead are as
     * far apart. One by one, each segment came in 3 responses, and 2 of them weren't read: 3x the bytes for as long as the player
     * stayed behind the head (2026-11-15).
     */
    @Test
    public void theSegmentsAskedForAheadAreAsFarApartAsAResponseHolds() throws Exception {
        RecordingFactory factory = new RecordingFactory(RESPONSE);
        SabrManifest manifest = liveManifest(1);
        DefaultSabrChunkSource source = source(manifest);
        source.enableLivePrefetch(factory, null);
        SabrStream sabrStream = manifest.getSabrStream(C.TRACK_TYPE_AUDIO);
        MediaChunk seek = firstChunk(source); // its response: 3 segments
        receive(sabrStream, SEQUENCE + 100, SEQUENCE, 3);
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(0, 0, Collections.singletonList(seek), out); // a seek's response says nothing: one by one
        assertEquals(3, factory.sources.size());
        receive(sabrStream, SEQUENCE + 100, SEQUENCE + 3, 3);

        source.getNextChunk(0, 0, Arrays.asList(seek, (MediaChunk) out.chunk), new ChunkHolder());

        assertEquals(6, factory.sources.size());
        for (int i = 0; i < 3; i++) { // the newest received is SEQUENCE + 5: SEQUENCE + 9, + 12 and + 15, each as if after the one before it
            VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(opened(factory.sources.get(3 + i)).httpBody);
            assertEquals(SEQUENCE + 8 + 3 * i, request.getBufferedRanges(0).getStartSegmentIndex());
        }
    }

    @Test
    public void aStreamWithLongerSegmentsIsntPrefetched() throws Exception {
        RecordingFactory factory = new RecordingFactory(RESPONSE);
        SabrManifest manifest = liveManifest(5);
        DefaultSabrChunkSource source = source(manifest);
        source.enableLivePrefetch(factory, null);
        MediaChunk previous = firstChunk(source);
        receive(manifest.getSabrStream(C.TRACK_TYPE_AUDIO), SEQUENCE + 10);

        source.getNextChunk(0, 0, Collections.singletonList(previous), new ChunkHolder());

        assertTrue(factory.sources.isEmpty());
    }

    @Test
    public void aSeekDropsWhatsAskedForAhead() throws Exception {
        RecordingFactory factory = new RecordingFactory(RESPONSE);
        SabrManifest manifest = liveManifest(1);
        DefaultSabrChunkSource source = source(manifest);
        source.enableLivePrefetch(factory, null);
        MediaChunk previous = firstChunk(source);
        receive(manifest.getSabrStream(C.TRACK_TYPE_AUDIO), SEQUENCE + 10);
        source.getNextChunk(0, 0, Collections.singletonList(previous), new ChunkHolder());
        assertEquals(3, factory.sources.size());

        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(0, 0, Collections.emptyList(), out);

        assertFalse(out.chunk == null);
        assertEquals(3, factory.sources.size()); // no more asked for: the stream is reset by the seek
    }

    /**
     * Its request, once the prefetch's thread opened it
     */
    private static DataSpec opened(RecordingDataSource source) throws InterruptedException {
        for (int i = 0; i < 200 && source.dataSpec == null; i++) {
            Thread.sleep(10);
        }
        return source.dataSpec;
    }

    private static MediaChunk firstChunk(DefaultSabrChunkSource source) {
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(0, 0, Collections.emptyList(), out);
        return (MediaChunk) out.chunk;
    }

    /**
     * The stream receives a live response: the format's metadata, the live metadata (its head), a segment
     */
    private static void receive(SabrStream sabrStream, int headSequenceNumber) throws Exception {
        receive(sabrStream, headSequenceNumber, SEQUENCE, 1);
    }

    /**
     * The stream receives a live response of several segments, a second each
     */
    private static void receive(SabrStream sabrStream, int headSequenceNumber, int firstSequence, int segments) throws Exception {
        FormatId formatId = FormatId.newBuilder().setItag(ITAG).build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        part(out, UMPPartId.FORMAT_INITIALIZATION_METADATA, FormatInitializationMetadata.newBuilder()
                .setFormatId(formatId).setMimeType("audio/mp4; codecs=\"mp4a.40.2\"").build().toByteArray());
        part(out, UMPPartId.LIVE_METADATA, LiveMetadata.newBuilder()
                .setHeadSequenceNumber(headSequenceNumber).setHeadSequenceTimeMs(START_MS + 30_000).build().toByteArray());
        for (int i = 0; i < segments; i++) {
            int sequence = firstSequence + i;
            part(out, UMPPartId.MEDIA_HEADER, MediaHeader.newBuilder().setHeaderId(i).setItag(ITAG).setFormatId(formatId)
                    .setSequenceNumber(sequence).setStartMs(START_MS + (sequence - SEQUENCE) * 1_000L).build().toByteArray());
            part(out, UMPPartId.MEDIA, new byte[] {(byte) i, 7, 7});
            part(out, UMPPartId.MEDIA_END, new byte[] {(byte) i});
        }

        SabrExtractorInput input = new SabrExtractorInput(sabrStream);
        input.init(new FakeExtractorInput.Builder().setData(out.toByteArray()).build());
        byte[] buffer = new byte[16];
        while (input.read(buffer, 0, buffer.length) != C.RESULT_END_OF_INPUT) {
            // the segment's media
        }
    }

    private static void part(ByteArrayOutputStream out, int partId, byte[] payload) throws Exception {
        out.write(partId);
        out.write(payload.length);
        out.write(payload);
    }

    private static SabrManifest liveManifest(int targetDurationSec) {
        Format audio = Format.createAudioContainerFormat(
                String.valueOf(ITAG), null, MimeTypes.AUDIO_MP4, MimeTypes.AUDIO_AAC, null, null,
                144_000, 2, 44_100, null, 0, 0, "en");
        Representation representation = Representation.newInstance(audio, SABR_URL,
                new SegmentTemplate(null, 1_000_000, 0, 0, C.INDEX_UNSET, targetDurationSec * 1_000_000L, null, null, null));
        Period period = new Period("test", 0,
                Collections.singletonList(new AdaptationSet(0, C.TRACK_TYPE_AUDIO, Collections.singletonList(representation))));
        return new SabrManifest(
                C.TIME_UNSET, 48 * 60 * 60 * 1_000L, 1_500, false,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                Collections.singletonList(period), SABR_URL, "", null, "test",
                ClientInfo.getDefaultInstance(), null, targetDurationSec);
    }

    private static DefaultSabrChunkSource source(SabrManifest manifest) {
        AdaptationSet adaptationSet = manifest.getPeriod(0).adaptationSets.get(0);
        return new DefaultSabrChunkSource(
                new LoaderErrorThrower.Dummy(), manifest, 0, new int[] {0},
                new FixedTrackSelection(new TrackGroup(adaptationSet.representations.get(0).format), 0), C.TRACK_TYPE_AUDIO,
                new RecordingDataSource(new byte[0]), 0, 1, false, Collections.emptyList(), null);
    }

    private static DataSpec spec() {
        return new DataSpec(Uri.parse(SABR_URL));
    }

    private static byte[] readAll(DataSource dataSource) throws IOException {
        dataSource.open(spec());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2];
        int read;
        while ((read = dataSource.read(buffer, 0, buffer.length)) != C.RESULT_END_OF_INPUT) {
            out.write(buffer, 0, read);
        }
        dataSource.close();
        return out.toByteArray();
    }

    /**
     * Its sources answer with the response, or fail for none
     */
    private static final class RecordingFactory implements DataSource.Factory {
        @Nullable private final byte[] response;
        final List<RecordingDataSource> sources = Collections.synchronizedList(new ArrayList<>());

        RecordingFactory(@Nullable byte[] response) {
            this.response = response;
        }

        @Override
        public DataSource createDataSource() {
            RecordingDataSource source = new RecordingDataSource(response);
            sources.add(source);
            return source;
        }
    }

    private static final class RecordingDataSource implements DataSource {
        @Nullable private final byte[] response;
        volatile int opened;
        @Nullable volatile DataSpec dataSpec;
        private int position;

        RecordingDataSource(@Nullable byte[] response) {
            this.response = response;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            this.dataSpec = dataSpec;
            opened++;
            if (response == null) {
                throw new IOException("test");
            }
            position = 0;
            return response.length;
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) {
            if (response == null || position >= response.length) {
                return C.RESULT_END_OF_INPUT;
            }
            int length = Math.min(readLength, response.length - position);
            System.arraycopy(response, position, buffer, offset, length);
            position += length;
            return length;
        }

        @Nullable
        @Override
        public Uri getUri() {
            return null;
        }

        @Override
        public void close() {
        }
    }
}
