package com.google.android.exoplayer2.source.sabr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.chunk.ChunkHolder;
import com.google.android.exoplayer2.source.chunk.ContainerMediaChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.sabr.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.sabr.manifest.Period;
import com.google.android.exoplayer2.source.sabr.manifest.Representation;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.manifest.SegmentBase;
import com.google.android.exoplayer2.source.sabr.manifest.SegmentBase.SegmentTemplate;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.BufferedRange;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.google.android.exoplayer2.trackselection.FixedTrackSelection;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * A live stream over SABR: its timeline starts at its start segment's media time (as the DASH live manifest's), and at its head the
 * next request waits instead of looping
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class DefaultSabrChunkSourceLiveTest {
    private static final String SABR_URL = "https://rr1---sn-primary.googlevideo.com/videoplayback?source=yt_live_broadcast";
    /**
     * The start segment's media time of a 23-day stream (ROboUHfPUrU, 2026-11-15): 24 h before its head
     */
    private static final long START_SEGMENT_TIME_US = 1_957_547_035_080L;
    private static final long LIVE_POSITION_US = 86_400_000_000L;
    private static final long WINDOW_MS = 48 * 60 * 60 * 1_000L;

    @Test
    public void aLiveSeekAsksTheServerForTheMediaTime() throws Exception {
        SabrManifest manifest = liveManifest(5, audio());
        ChunkHolder out = new ChunkHolder();

        source(manifest, 0, new FailingDataSource()).getNextChunk(LIVE_POSITION_US, LIVE_POSITION_US, Collections.emptyList(), out);

        assertTrue(out.chunk instanceof ContainerMediaChunk);
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(out.chunk.dataSpec.httpBody);
        assertEquals((LIVE_POSITION_US + START_SEGMENT_TIME_US) / 1_000, request.getClientAbrState().getPlayerTimeMs());
        // On the timeline, not the media's: the player's buffered duration is computed from it
        assertEquals(LIVE_POSITION_US, ((MediaChunk) out.chunk).startTimeUs);
    }

    @Test
    public void aVideoSeekAsksTheServerForTheTimelinesTime() throws Exception {
        SabrManifest manifest = manifest(SabrManifest.NOT_LIVE, 0, audio());
        ChunkHolder out = new ChunkHolder();

        source(manifest, 0, new FailingDataSource()).getNextChunk(30_000_000, 30_000_000, Collections.emptyList(), out);

        assertEquals(30_000, VideoPlaybackAbrRequest.parseFrom(out.chunk.dataSpec.httpBody).getClientAbrState().getPlayerTimeMs());
    }

    /**
     * Answered with no media (the segment after the head's doesn't exist yet), the request asked again at once got the same answer, tens
     * a second, until the app reloaded the stream (seen 2026-11-15). It waits a quarter of a segment.
     */
    @Test
    public void aLiveRequestAnsweredWithNoSegmentIsRetriedLater() throws Exception {
        FailingDataSource upstream = new FailingDataSource();
        DefaultSabrChunkSource source = source(liveManifest(1, audio()), 0, upstream);
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(LIVE_POSITION_US, LIVE_POSITION_US, Collections.emptyList(), out);
        MediaChunk seek = (MediaChunk) out.chunk;

        source.onChunkLoadCompleted(seek); // no segment came
        out.clear();
        source.getNextChunk(LIVE_POSITION_US, LIVE_POSITION_US, Collections.singletonList(seek), out);

        long waitedMs = msUntilOpened(out.chunk, upstream);
        assertTrue("waited " + waitedMs + " ms", waitedMs >= 200);
    }

    /**
     * The wait is told, with its track: a start at the head that waited for the stream had no row for it (AFQQ49Z4 RJFwMQBXPUQ)
     */
    @Test
    public void aLiveRequestAnsweredWithNoSegmentTellsItsWait() throws Exception {
        long[] told = {-1, -1};
        DefaultSabrChunkSource.setLiveHeadWaitListener((trackType, waitMs) -> {
            told[0] = trackType;
            told[1] = waitMs;
        });

        try {
            DefaultSabrChunkSource source = source(liveManifest(4, audio()), 0, new FailingDataSource());
            ChunkHolder out = new ChunkHolder();
            source.getNextChunk(LIVE_POSITION_US, LIVE_POSITION_US, Collections.emptyList(), out);

            source.onChunkLoadCompleted(out.chunk); // no segment came

            assertEquals(C.TRACK_TYPE_AUDIO, told[0]);
            assertEquals(1_000, told[1]); // a quarter of the 4 s segments
        } finally {
            DefaultSabrChunkSource.setLiveHeadWaitListener(null);
        }
    }

    @Test
    public void aSeekAfterAnAnswerWithNoSegmentAsksAtOnce() throws Exception {
        FailingDataSource upstream = new FailingDataSource();
        DefaultSabrChunkSource source = source(liveManifest(1, audio()), 0, upstream);
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(LIVE_POSITION_US, LIVE_POSITION_US, Collections.emptyList(), out);
        source.onChunkLoadCompleted(out.chunk);
        out.clear();

        source.getNextChunk(LIVE_POSITION_US - 10_000_000, LIVE_POSITION_US - 10_000_000, Collections.emptyList(), out);

        long waitedMs = msUntilOpened(out.chunk, upstream);
        assertTrue("waited " + waitedMs + " ms", waitedMs < 150);
    }

    @Test
    public void aVideoRequestAnsweredWithNoSegmentIsntDelayed() throws Exception {
        FailingDataSource upstream = new FailingDataSource();
        DefaultSabrChunkSource source = source(manifest(SabrManifest.NOT_LIVE, 0, audio()), 0, upstream);
        ChunkHolder out = new ChunkHolder();
        source.getNextChunk(0, 0, Collections.emptyList(), out);
        MediaChunk first = (MediaChunk) out.chunk;
        source.onChunkLoadCompleted(first);
        out.clear();

        source.getNextChunk(0, 0, Collections.singletonList(first), out);

        long waitedMs = msUntilOpened(out.chunk, upstream);
        assertTrue("waited " + waitedMs + " ms", waitedMs < 150);
    }

    @Test
    public void theWaitEndsWhenTheLoadIsCanceled() throws Exception {
        DefaultSabrChunkSource.DelayedOpenDataSource dataSource =
                new DefaultSabrChunkSource.DelayedOpenDataSource(new FailingDataSource(), SystemClock.elapsedRealtime() + 60_000);
        Thread.currentThread().interrupt(); // as the loader does when it cancels

        try {
            dataSource.open(new DataSpec(Uri.parse(SABR_URL)));
            fail();
        } catch (InterruptedIOException expected) {
            assertTrue(Thread.interrupted());
        }
    }

    /**
     * The other track's format, marked buffered for good so that the server sends none of it: a live stream's server goes by the segment
     * numbers, so from the first one; a video's server sent media headers with no format metadata for [0, MAX], so it keeps [MAX, MAX]
     */
    @Test
    public void theOtherTracksFormatIsBufferedFromTheFirstSegmentOfALiveStream() throws Exception {
        assertEquals(0, otherTracksRange(liveManifest(5, video(), audio())).getStartSegmentIndex());
        assertEquals(Integer.MAX_VALUE, otherTracksRange(manifest(SabrManifest.NOT_LIVE, 0, video(), audio())).getStartSegmentIndex());
    }

    private static BufferedRange otherTracksRange(SabrManifest manifest) throws Exception {
        source(manifest, 0, new FailingDataSource());
        source(manifest, 1, new FailingDataSource());

        VideoPlaybackAbrRequest request = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_VIDEO, false);

        assertEquals(1, request.getBufferedRangesCount());
        assertEquals(140, request.getBufferedRanges(0).getFormatId().getItag());
        return request.getBufferedRanges(0);
    }

    /**
     * Loads the chunk until its data source opens (the upstream fails there)
     */
    private static long msUntilOpened(@Nullable Object chunk, FailingDataSource upstream) throws Exception {
        assertTrue(chunk instanceof ContainerMediaChunk);
        long startNs = System.nanoTime();

        try {
            ((ContainerMediaChunk) chunk).load();
            fail();
        } catch (IOException expected) {
            // the upstream's
        }

        return (upstream.openedNs - startNs) / 1_000_000;
    }

    private static Format audio() {
        return Format.createAudioContainerFormat(
                "140", null, MimeTypes.AUDIO_MP4, MimeTypes.AUDIO_AAC, null, null,
                144_000, 2, 44_100, null, 0, 0, "en");
    }

    private static Format video() {
        return Format.createVideoContainerFormat(
                "248", null, MimeTypes.VIDEO_WEBM, MimeTypes.VIDEO_VP9, null, null,
                2_900_000, 1920, 1080, 30, null, 0, 0);
    }

    private static SabrManifest liveManifest(int targetDurationSec, Format... formats) {
        return manifest(targetDurationSec, START_SEGMENT_TIME_US, formats);
    }

    private static SabrManifest manifest(int liveTargetDurationSec, long presentationTimeOffsetUs, Format... formats) {
        boolean isLive = liveTargetDurationSec != SabrManifest.NOT_LIVE;
        AdaptationSet[] adaptationSets = new AdaptationSet[formats.length];

        for (int i = 0; i < formats.length; i++) {
            SegmentBase segmentBase = isLive
                    ? new SegmentTemplate(null, 1_000_000, presentationTimeOffsetUs, 0, C.INDEX_UNSET,
                            liveTargetDurationSec * 1_000_000L, null, null, null)
                    : new SegmentBase.SingleSegmentBase();
            int type = MimeTypes.isVideo(formats[i].sampleMimeType) ? C.TRACK_TYPE_VIDEO : C.TRACK_TYPE_AUDIO;
            adaptationSets[i] = new AdaptationSet(i, type,
                    Collections.singletonList(Representation.newInstance(formats[i], SABR_URL, segmentBase)));
        }

        Period period = new Period("test", 0, Arrays.asList(adaptationSets));
        return new SabrManifest(
                C.TIME_UNSET, isLive ? WINDOW_MS : 60_000, 1_500, false,
                C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET, C.TIME_UNSET,
                Collections.singletonList(period), SABR_URL, "", null, "test",
                ClientInfo.getDefaultInstance(), null, liveTargetDurationSec);
    }

    private static DefaultSabrChunkSource source(SabrManifest manifest, int adaptationSetIndex, DataSource dataSource) {
        AdaptationSet adaptationSet = manifest.getPeriod(0).adaptationSets.get(adaptationSetIndex);
        Format format = adaptationSet.representations.get(0).format;
        return new DefaultSabrChunkSource(
                new LoaderErrorThrower.Dummy(), manifest, 0, new int[] {adaptationSetIndex},
                new FixedTrackSelection(new TrackGroup(format), 0), adaptationSet.type,
                dataSource, 0, 1, false, Collections.emptyList(), null);
    }

    /**
     * Fails when opened, and tells when
     */
    private static final class FailingDataSource implements DataSource {
        private volatile long openedNs;

        @Override
        public void addTransferListener(TransferListener transferListener) {
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            openedNs = System.nanoTime();
            throw new IOException("test");
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) {
            return C.RESULT_END_OF_INPUT;
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
