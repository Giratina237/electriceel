package com.google.android.exoplayer2.source.sabr;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.CheckResult;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.SeekParameters;
import com.google.android.exoplayer2.extractor.Extractor;
import com.google.android.exoplayer2.extractor.TrackOutput;
import com.google.android.exoplayer2.extractor.rawcc.RawCcExtractor;
import com.google.android.exoplayer2.source.BehindLiveWindowException;
import com.google.android.exoplayer2.source.chunk.BaseMediaChunkOutput;
import com.google.android.exoplayer2.source.chunk.Chunk;
import com.google.android.exoplayer2.source.chunk.ChunkExtractorWrapper;
import com.google.android.exoplayer2.source.chunk.ChunkHolder;
import com.google.android.exoplayer2.source.chunk.ContainerMediaChunk;
import com.google.android.exoplayer2.source.chunk.InitializationChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunk;
import com.google.android.exoplayer2.source.chunk.MediaChunkIterator;
import com.google.android.exoplayer2.source.chunk.SingleSampleMediaChunk;
import com.google.android.exoplayer2.source.sabr.PlayerEmsgHandler.PlayerTrackEmsgHandler;
import com.google.android.exoplayer2.source.sabr.manifest.AdaptationSet;
import com.google.android.exoplayer2.source.sabr.manifest.RangedUri;
import com.google.android.exoplayer2.source.sabr.manifest.Representation;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.parser.adapter.SabrFragmentedMp4Adapter;
import com.google.android.exoplayer2.source.sabr.parser.adapter.SabrMatroskaAdapter;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.PoTokenError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrProtocolError;
import com.google.android.exoplayer2.source.sabr.parser.models.AudioSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.CaptionSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.FormatSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.VideoSelector;
import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.MediaHeader;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.LoaderErrorThrower;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.MimeTypes;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DefaultSabrChunkSource implements SabrChunkSource {
    public static final class Factory implements SabrChunkSource.Factory {

        private final DataSource.Factory dataSourceFactory;
        private final int maxSegmentsPerLoad;

        public Factory(DataSource.Factory dataSourceFactory) {
            this(dataSourceFactory, 1);
        }

        public Factory(DataSource.Factory dataSourceFactory, int maxSegmentsPerLoad) {
            this.dataSourceFactory = dataSourceFactory;
            this.maxSegmentsPerLoad = maxSegmentsPerLoad;
        }

        @Override
        public SabrChunkSource createSabrChunkSource(
                LoaderErrorThrower manifestLoaderErrorThrower,
                SabrManifest manifest,
                int periodIndex,
                int[] adaptationSetIndices,
                TrackSelection trackSelection,
                int trackType,
                long elapsedRealtimeOffsetMs,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                @Nullable PlayerTrackEmsgHandler playerEmsgHandler,
                @Nullable TransferListener transferListener) {
            DataSource dataSource = dataSourceFactory.createDataSource();
            if (transferListener != null) {
                dataSource.addTransferListener(transferListener);
            }
            DefaultSabrChunkSource source = new DefaultSabrChunkSource(
                    manifestLoaderErrorThrower,
                    manifest,
                    periodIndex,
                    adaptationSetIndices,
                    trackSelection,
                    trackType,
                    dataSource,
                    elapsedRealtimeOffsetMs,
                    maxSegmentsPerLoad,
                    enableEventMessageTrack,
                    closedCaptionFormats,
                    playerEmsgHandler);
            source.enableLivePrefetch(dataSourceFactory, transferListener);
            return source;
        }

    }

    private static final String TAG = DefaultSabrChunkSource.class.getSimpleName();
    private static final long END_OF_STREAM_SEEK_TOLERANCE_US = 500_000L; // 0.5 seconds
    private static final long NEAR_END_TOLERANCE_US = 700_000L; // 0.7 seconds
    /**
     * Of a media segment, about: the first media chunk's length, before any segment of its format is received
     */
    private static final long DEFAULT_SEGMENT_DURATION_MS = 5_000;
    /**
     * Of a live stream at its head, the wait for its next segment (see maybeWaitForLiveSegment)
     */
    private static final long MIN_LIVE_WAIT_MS = 250;
    private static final long MAX_LIVE_WAIT_MS = 10_000;
    private final LoaderErrorThrower manifestLoaderErrorThrower;
    private final int[] adaptationSetIndices;
    private final int trackType;
    private final DataSource dataSource;
    private final long elapsedRealtimeOffsetMs;
    private final int maxSegmentsPerLoad;
    @Nullable private final PlayerTrackEmsgHandler playerTrackEmsgHandler;

    protected final RepresentationHolder[] representationHolders;

    private TrackSelection trackSelection;
    private FormatSelector formatSelector;
    private SabrManifest manifest;
    private int periodIndex;
    private IOException fatalError;
    private boolean missingLastSegment;
    private long liveEdgeTimeUs;

    @Nullable private final SabrStream sabrStream;
    @Nullable private final SabrSampleDeduplicator sampleDeduplicator;
    private final Map<String, String> sabrHeaders;
    private int nexChunkIdx = -1;
    private long pendingSeekTimeUs = C.TIME_UNSET;
    /**
     * Of a live stream: the next media chunk's request isn't sent before (elapsed realtime), see maybeWaitForLiveSegment
     */
    private long nextLiveLoadTimeMs = C.TIME_UNSET;
    /**
     * Of the last media chunk's request: its format's itag and the sequence number of the segment received last before it
     */
    private int liveRequestItag = -1;
    private long liveRequestSequenceNumber = -1;
    /**
     * Of a live stream with short segments (see SabrLivePrefetcher), null otherwise
     */
    @Nullable private SabrLivePrefetcher livePrefetcher;
    /**
     * How many segments of the format the last response held (see maybePrefetch): 1 till one says, and after a seek
     */
    private int liveSegmentsPerResponse = 1;
    /**
     * The newest segment's number when the last chunk was made, -1 for none since a seek
     */
    private long livePrefetchSequenceNumber = -1;

    /**
     * @param manifestLoaderErrorThrower Throws errors affecting loading of manifests.
     * @param manifest The initial manifest.
     * @param periodIndex The index of the period in the manifest.
     * @param adaptationSetIndices The indices of the adaptation sets in the period.
     * @param trackSelection The track selection.
     * @param trackType The type of the tracks in the selection.
     * @param dataSource A {@link DataSource} suitable for loading the media data.
     * @param elapsedRealtimeOffsetMs If known, an estimate of the instantaneous difference between
     *     server-side unix time and {@link SystemClock#elapsedRealtime()} in milliseconds, specified
     *     as the server's unix time minus the local elapsed time. If unknown, set to 0.
     * @param maxSegmentsPerLoad The maximum number of segments to combine into a single request. Note
     *     that segments will only be combined if their {@link Uri}s are the same and if their data
     *     ranges are adjacent.
     * @param enableEventMessageTrack Whether to output an event message track.
     * @param closedCaptionFormats The {@link Format Formats} of closed caption tracks to be output.
     * @param playerTrackEmsgHandler The {@link PlayerTrackEmsgHandler} instance to handle emsg
     *     messages targeting the player. Maybe null if this is not necessary.
     */
    public DefaultSabrChunkSource(
            LoaderErrorThrower manifestLoaderErrorThrower,
            SabrManifest manifest,
            int periodIndex,
            int[] adaptationSetIndices,
            TrackSelection trackSelection,
            int trackType,
            DataSource dataSource,
            long elapsedRealtimeOffsetMs,
            int maxSegmentsPerLoad,
            boolean enableEventMessageTrack,
            List<Format> closedCaptionFormats,
            @Nullable PlayerTrackEmsgHandler playerTrackEmsgHandler) {
        this.manifestLoaderErrorThrower = manifestLoaderErrorThrower;
        this.manifest = manifest;
        this.adaptationSetIndices = adaptationSetIndices;
        this.trackSelection = trackSelection;
        this.formatSelector = createFormatSelector(trackType, trackSelection);
        this.trackType = trackType;
        this.dataSource = dataSource;
        this.periodIndex = periodIndex;
        this.elapsedRealtimeOffsetMs = elapsedRealtimeOffsetMs;
        this.maxSegmentsPerLoad = maxSegmentsPerLoad;
        this.playerTrackEmsgHandler = playerTrackEmsgHandler;

        long periodDurationUs = manifest.getPeriodDurationUs(periodIndex);
        liveEdgeTimeUs = C.TIME_UNSET;
        
        sabrHeaders = new HashMap<>();
        sabrHeaders.put("Content-Type", "application/x-protobuf");
        //sabrHeaders.put("Accept-Encoding", "identity");
        sabrHeaders.put("Accept", "application/vnd.yt-ump");

        List<Representation> representations = getRepresentations();
        SabrStream stream = null;
        representationHolders = new RepresentationHolder[trackSelection.length()];
        for (int i = 0; i < representationHolders.length; i++) {
            Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
            if (stream == null && RepresentationHolder.needsExtractor(representation)) {
                stream = manifest.getSabrStream(trackType);
                stream.setFormatSelector(formatSelector);
            }
            representationHolders[i] =
                    new RepresentationHolder(
                            periodDurationUs,
                            trackType,
                            representation,
                            enableEventMessageTrack,
                            closedCaptionFormats,
                            playerTrackEmsgHandler,
                            stream);
        }
        this.sabrStream = stream;
        this.sampleDeduplicator = stream != null ? new SabrSampleDeduplicator(stream) : null;
    }

    /**
     * Of a live stream with segments of up to 2 s: the next ones are asked for while the player loads one (see SabrLivePrefetcher)
     */
    /* package */ void enableLivePrefetch(DataSource.Factory dataSourceFactory, @Nullable TransferListener transferListener) {
        if (sabrStream != null && manifest.isLive()
                && manifest.liveSegmentTargetDurationSec <= SabrLivePrefetcher.MAX_SEGMENT_DURATION_SEC) {
            livePrefetcher = new SabrLivePrefetcher(dataSourceFactory, transferListener);
        }
    }

    @Override
    public void updateManifest(SabrManifest newManifest, int newPeriodIndex) {
        try {
            manifest = newManifest;
            periodIndex = newPeriodIndex;
            long periodDurationUs = manifest.getPeriodDurationUs(periodIndex);
            List<Representation> representations = getRepresentations();
            for (int i = 0; i < representationHolders.length; i++) {
                Representation representation = representations.get(trackSelection.getIndexInTrackGroup(i));
                representationHolders[i] =
                        representationHolders[i].copyWithNewRepresentation(periodDurationUs, representation);
            }
        } catch (BehindLiveWindowException e) {
            fatalError = e;
        }
    }

    @Override
    public void updateTrackSelection(TrackSelection trackSelection) {
        this.trackSelection = trackSelection;
        this.formatSelector = createFormatSelector(trackType, trackSelection);
    }

    @Override
    public long getAdjustedSeekPositionUs(long positionUs, SeekParameters seekParameters) {
        // Segments are aligned across representations, so any segment index will do.
        //for (RepresentationHolder representationHolder : representationHolders) {
        //    if (representationHolder.segmentIndex != null) {
        //        long segmentNum = representationHolder.getSegmentNum(positionUs);
        //        long firstSyncUs = representationHolder.getSegmentStartTimeUs(segmentNum);
        //        long secondSyncUs =
        //                firstSyncUs < positionUs && segmentNum < representationHolder.getSegmentCount() - 1
        //                        ? representationHolder.getSegmentStartTimeUs(segmentNum + 1)
        //                        : firstSyncUs;
        //        return Util.resolveSeekPositionUs(positionUs, seekParameters, firstSyncUs, secondSyncUs);
        //    }
        //}

        // Clamp a seek target that lands at/past the end of the video. Without this, seeking to
        // exactly periodDurationUs (e.g. the app's "pause on end" logic parking the player at
        // getDurationMs()) leaves nothing left to buffer, so getNextChunk() immediately re-signals
        // endOfStream, which re-triggers STATE_ENDED, which re-triggers that same seek-to-end logic
        // again - an infinite onTracksChanged()/STATE_ENDED loop every time playback reaches the end.
        for (RepresentationHolder representationHolder : representationHolders) {
            long periodDurationUs = representationHolder.periodDurationUs;
            if (periodDurationUs != C.TIME_UNSET && positionUs >= periodDurationUs) {
                // The constant is usually smaller than the real value in sabrStream.getEndOfStreamSeekToleranceMs()
                return Math.max(0, periodDurationUs - END_OF_STREAM_SEEK_TOLERANCE_US);
            }
        }
        // We don't have a segment index to adjust the seek position with yet.
        return positionUs;
    }

    @Override
    public void maybeThrowError() throws IOException {
        if (fatalError != null) {
            throw fatalError;
        } else {
            manifestLoaderErrorThrower.maybeThrowError();
        }
    }

    @Override
    public int getPreferredQueueSize(long playbackPositionUs, List<? extends MediaChunk> queue) {
        if (fatalError != null || trackSelection.length() < 2) {
            return queue.size();
        }
        int preferredQueueSize = trackSelection.evaluateQueueSize(playbackPositionUs, queue);
        if (preferredQueueSize < queue.size() && sampleDeduplicator != null) {
            sampleDeduplicator.reset(); // the queued chunks past the preferred size may be discarded
        }
        return preferredQueueSize;
    }

    @Override
    public void getNextChunk(long playbackPositionUs, long loadPositionUs, List<? extends MediaChunk> queue, ChunkHolder out) {
        if (fatalError != null) {
            return;
        }

        long bufferedDurationUs = loadPositionUs - playbackPositionUs;
        long timeToLiveEdgeUs = resolveTimeToLiveEdgeUs(playbackPositionUs);
        long presentationPositionUs =
                C.msToUs(manifest.availabilityStartTimeMs)
                        + C.msToUs(manifest.getPeriod(periodIndex).startMs)
                        + loadPositionUs;

        if (playerTrackEmsgHandler != null
                && playerTrackEmsgHandler.maybeRefreshManifestBeforeLoadingNextChunk(
                presentationPositionUs)) {
            return;
        }

        long nowUnixTimeUs = getNowUnixTimeUs();
        MediaChunk previous = queue.isEmpty() ? null : queue.get(queue.size() - 1);
        MediaChunkIterator[] chunkIterators = new MediaChunkIterator[trackSelection.length()];
        //for (int i = 0; i < chunkIterators.length; i++) {
        //    RepresentationHolder representationHolder = representationHolders[i];
        //    if (representationHolder.segmentIndex == null) {
        //        chunkIterators[i] = MediaChunkIterator.EMPTY;
        //    } else {
        //        long firstAvailableSegmentNum =
        //                representationHolder.getFirstAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //        long lastAvailableSegmentNum =
        //                representationHolder.getLastAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //        long segmentNum =
        //                getSegmentNum(
        //                        representationHolder,
        //                        previous,
        //                        loadPositionUs,
        //                        firstAvailableSegmentNum,
        //                        lastAvailableSegmentNum);
        //        if (segmentNum < firstAvailableSegmentNum) {
        //            chunkIterators[i] = MediaChunkIterator.EMPTY;
        //        } else {
        //            chunkIterators[i] =
        //                    new RepresentationSegmentIterator(
        //                            representationHolder, segmentNum, lastAvailableSegmentNum);
        //        }
        //    }
        //}

        trackSelection.updateSelectedTrack(
                playbackPositionUs, bufferedDurationUs, timeToLiveEdgeUs, queue, chunkIterators);

        RepresentationHolder representationHolder =
                representationHolders[trackSelection.getSelectedIndex()];

        // External captions contain the entire subtitle track in one sample.
        if (representationHolder.extractorWrapper == null && previous != null) {
            out.endOfStream = true;
            return;
        }

        if (representationHolder.extractorWrapper != null) {
            Representation selectedRepresentation = representationHolder.representation;
            RangedUri pendingInitializationUri = null;
            RangedUri pendingIndexUri = null;
            if (representationHolder.extractorWrapper.getSampleFormats() == null) {
                pendingInitializationUri = selectedRepresentation.getInitializationUri();
            }
            // No segment index in SABR
            //if (representationHolder.segmentIndex == null) {
            //    pendingIndexUri = selectedRepresentation.getIndexUri();
            //}
            //if (pendingInitializationUri != null || pendingIndexUri != null) {
            if (pendingInitializationUri != null) {
                // We have initialization and/or index requests to make.
                out.chunk = newInitializationChunk(representationHolder, dataSource,
                        trackSelection.getSelectedFormat(), trackSelection.getSelectionReason(),
                        trackSelection.getSelectionData(), pendingInitializationUri, pendingIndexUri);
                return;
            }
        }

        long periodDurationUs = representationHolder.periodDurationUs;
        boolean periodEnded = periodDurationUs != C.TIME_UNSET;

        // FIX: fire ending event on a video end
        // Tolerance needed: on some (usually short/sponsor at end) videos, real segment duration falls a fraction
        // of a second short of periodDurationUs, so a strict ">=" never fires and getNextChunk() loops forever
        if (periodEnded && (loadPositionUs + NEAR_END_TOLERANCE_US) >= periodDurationUs) {
            // No segment index in SABR, so we can't compare per-segment boundaries like stock
            // DASH does — comparing loadPositionUs directly against periodDurationUs is the
            // SABR equivalent. Without this, getNextChunk() keeps firing "next chunk" requests
            // past the real end of the video forever, and the player never reaches STATE_ENDED.
            out.endOfStream = true;
            return;
        }

        //if (representationHolder.getSegmentCount() == 0) {
        //    // The index doesn't define any segments.
        //    out.endOfStream = periodEnded;
        //    return;
        //}

        //long firstAvailableSegmentNum =
        //        representationHolder.getFirstAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);
        //long lastAvailableSegmentNum =
        //        representationHolder.getLastAvailableSegmentNum(manifest, periodIndex, nowUnixTimeUs);

        //updateLiveEdgeTimeUs(representationHolder, lastAvailableSegmentNum);

        //long segmentNum =
        //        getSegmentNum(
        //                representationHolder,
        //                previous,
        //                loadPositionUs,
        //                firstAvailableSegmentNum,
        //                lastAvailableSegmentNum);
        //if (segmentNum < firstAvailableSegmentNum) {
        //    // This is before the first chunk in the current manifest.
        //    fatalError = new BehindLiveWindowException();
        //    return;
        //}
        //
        //if (segmentNum > lastAvailableSegmentNum
        //        || (missingLastSegment && segmentNum >= lastAvailableSegmentNum)) {
        //    // The segment is beyond the end of the period.
        //    out.endOfStream = periodEnded;
        //    return;
        //}

        //if (periodEnded && representationHolder.getSegmentStartTimeUs(segmentNum) >= periodDurationUs) {
        //    // The period duration clips the period to a position before the segment.
        //    out.endOfStream = true;
        //    return;
        //}

        //int maxSegmentCount =
        //        (int) Math.min(maxSegmentsPerLoad, lastAvailableSegmentNum - segmentNum + 1);
        //if (periodDurationUs != C.TIME_UNSET) {
        //    while (maxSegmentCount > 1
        //            && representationHolder.getSegmentStartTimeUs(segmentNum + maxSegmentCount - 1)
        //            >= periodDurationUs) {
        //        // The period duration clips the period to a position before the last segment in the range
        //        // [segmentNum, segmentNum + maxSegmentCount - 1]. Reduce maxSegmentCount.
        //        maxSegmentCount--;
        //    }
        //}

        long seekTimeUs = queue.isEmpty() ? loadPositionUs : C.TIME_UNSET;
        if (queue.isEmpty()) {
            nextLiveLoadTimeMs = C.TIME_UNSET; // a seek asks for another time, at once
        }
        if (queue.isEmpty() && sampleDeduplicator != null) {
            sampleDeduplicator.reset(); // nothing is queued
        }
        if (sabrStream != null) {
            if (seekTimeUs != C.TIME_UNSET) {
                pendingSeekTimeUs = seekTimeUs;
            } else if (pendingSeekTimeUs != C.TIME_UNSET) {
                FormatId formatId = formatSelector.getSelectedFormatId();
                if (sabrStream.hasSegmentStartTime(formatId != null ? formatId.getItag() : -1)) {
                    pendingSeekTimeUs = C.TIME_UNSET; // the seek landed
                } else {
                    // FIX: the seek chunk failed (e.g. retried on an alternate media network) before
                    // any segment arrived. The stream time is reset to 0 at this point, so without the
                    // seek target the server would send segments from the start of the video.
                    Log.w(TAG, "Retrying the seek to " + C.usToMs(pendingSeekTimeUs) + "ms");
                    seekTimeUs = pendingSeekTimeUs;
                }
            }
        }
        out.chunk =
                newMediaChunk(
                        representationHolder,
                        dataSource,
                        trackType,
                        trackSelection.getSelectedFormat(),
                        trackSelection.getSelectionReason(),
                        trackSelection.getSelectionData(),
                        C.INDEX_UNSET, // TODO: does sabr has segment num?
                        //segmentNum,
                        //maxSegmentCount,
                        seekTimeUs);
    }

    @Override
    public void onChunkLoadCompleted(Chunk chunk) {
        if (chunk instanceof InitializationChunk) {
            InitializationChunk initializationChunk = (InitializationChunk) chunk;
            int trackIndex = trackSelection.indexOf(initializationChunk.trackFormat);
            RepresentationHolder representationHolder = representationHolders[trackIndex];
            // The null check avoids overwriting an index obtained from the manifest with one obtained
            // from the stream. If the manifest defines an index then the stream shouldn't, but in cases
            // where it does we should ignore it.
            //if (representationHolder.segmentIndex == null) {
            //    SeekMap seekMap = representationHolder.extractorWrapper.getSeekMap();
            //    if (seekMap != null) {
            //        representationHolders[trackIndex] =
            //                representationHolder.copyWithNewSegmentIndex(
            //                        new SabrWrappingSegmentIndex(
            //                                (ChunkIndex) seekMap,
            //                                representationHolder.representation.presentationTimeOffsetUs));
            //    }
            //}
        }
        if (playerTrackEmsgHandler != null) {
            playerTrackEmsgHandler.onChunkLoadCompleted(chunk);
        }
        maybeWaitForLiveSegment(chunk);
        // FIX: the connection broke in the middle of the response, which ended the chunk early (see
        // SabrFragmentedMp4Adapter, SabrMatroskaAdapter). A media network that stalls is left as after a failed load.
        IOException responseError = sabrStream != null ? sabrStream.pollResponseError() : null;
        if (responseError != null && isEndpointConnectionFailure(chunk, responseError)
                && manifest.maybeUseNextCdn(chunk.dataSpec.uri.toString())) {
            Log.w(TAG, "Requesting the rest of the segment on an alternate media network");
        }
    }

    @Override
    public boolean onChunkLoadError(Chunk chunk, boolean cancelable, Exception e, long blacklistDurationMs) {
        Log.e(TAG, "Chunk load failed: " + e.getMessage());

        // A reload request is not a transport failure. Handing it back as "handled" would
        // retry the very same request at full speed. Let it fall through to the
        // LoadErrorHandlingPolicy, which stashes the token and fails the load.
        Throwable cause = e.getCause();
        if (cause instanceof SabrReloadPlayerError || cause instanceof SabrBackoffError || cause instanceof PoTokenError
                || cause instanceof SabrProtocolError) {
            Log.e(TAG, "SABR control signal, letting the error policy decide: " + cause.getMessage());
            return false;
        }

        if (!cancelable) {
            return false;
        }
        if (!(chunk instanceof SingleSampleMediaChunk) && isEndpointConnectionFailure(chunk, e)) {
            if (!ValidatedNetwork.isAvailable()) {
                // Offline: the failure says nothing about the media network, don't leave it or mark it failed
                Log.w(TAG, "No validated network, keeping the SABR media network");
                return false;
            }

            if (manifest.maybeUseNextCdn(chunk.dataSpec.uri.toString())) {
                Log.w(TAG, "Retrying SABR request on an alternate media network");
                resetSampleDeduplicator(); // the chunk is discarded with its samples
                return true;
            }

            // All representations use the same SABR endpoint. Do not rapidly
            // blacklist qualities when the endpoint itself is unreachable.
            return false;
        }
        if (playerTrackEmsgHandler != null
                && playerTrackEmsgHandler.maybeRefreshManifestOnLoadingError(chunk)) {
            resetSampleDeduplicator(); // the chunk is discarded with its samples
            return true;
        }
        // Workaround for missing segment at the end of the period
        //if (!manifest.dynamic && chunk instanceof MediaChunk
        //        && e instanceof InvalidResponseCodeException
        //        && ((InvalidResponseCodeException) e).responseCode == 404) {
        //    RepresentationHolder representationHolder =
        //            representationHolders[trackSelection.indexOf(chunk.trackFormat)];
        //    int segmentCount = representationHolder.getSegmentCount();
        //    if (segmentCount != SabrSegmentIndex.INDEX_UNBOUNDED && segmentCount != 0) {
        //        long lastAvailableSegmentNum = representationHolder.getFirstSegmentNum() + segmentCount - 1;
        //        if (((MediaChunk) chunk).getNextChunkIndex() > lastAvailableSegmentNum) {
        //            missingLastSegment = true;
        //            return true;
        //        }
        //    }
        //}
        boolean blacklisted = blacklistDurationMs != C.TIME_UNSET
                && trackSelection.blacklist(trackSelection.indexOf(chunk.trackFormat), blacklistDurationMs);
        if (blacklisted) {
            resetSampleDeduplicator(); // the chunk is discarded with its samples
        }
        return blacklisted;
    }

    /**
     * A live stream's request for the segment after its newest is answered with no media while that segment doesn't exist (at once,
     * or after the server held it a few seconds). Asked again at once, the same: requests in a loop, tens a second (seen 2026-11-15,
     * the audio track's, until the app reloaded the stream). The next one waits the backoff the server asks for (see
     * NextRequestPolicy), else a quarter of a segment.
     */
    private void maybeWaitForLiveSegment(Chunk chunk) {
        if (!manifest.isLive() || sabrStream == null || !(chunk instanceof MediaChunk) || liveRequestItag == -1) {
            return;
        }

        int itag = liveRequestItag;
        liveRequestItag = -1;

        // After a seek, the stream has none until one comes (see SabrStream#reset)
        if (sabrStream.hasSegmentStartTime(itag) && getLastSequenceNumber(itag) > liveRequestSequenceNumber) {
            return; // a new segment came
        }

        int backoffMs = sabrStream.getBackoffTimeMs();
        long waitMs = backoffMs > 0
                ? Math.min(backoffMs, MAX_LIVE_WAIT_MS)
                : Math.max(MIN_LIVE_WAIT_MS, manifest.liveSegmentTargetDurationSec * 1_000L / 4);
        nextLiveLoadTimeMs = SystemClock.elapsedRealtime() + waitMs;
        Log.d(TAG, "No new live segment: the next request waits " + waitMs + " ms");
    }

    /**
     * Of a live stream with short segments: the request for the next segment is served by its response asked for ahead, if any, and
     * the ones after it are asked for, up to the head (see SabrLivePrefetcher)
     *
     * @return the data source of the chunk's request
     */
    private DataSource maybePrefetch(int trackType, int iTag, boolean isSeek) {
        SabrLivePrefetcher prefetcher = livePrefetcher;

        if (prefetcher == null || sabrStream == null) {
            return dataSource;
        }

        if (isSeek) {
            prefetcher.clear(); // another time
            liveSegmentsPerResponse = 1;
            livePrefetchSequenceNumber = -1;
            return dataSource;
        }

        MediaHeader last = sabrStream.getInitializedFormat(iTag);

        if (last == null || !sabrStream.hasSegmentStartTime(iTag) || !last.hasSequenceNumber()) {
            return dataSource;
        }

        updateLiveSegmentsPerResponse(last.getSequenceNumber());
        DataSource chunkDataSource = prefetcher.dataSourceFor(iTag, last.getSequenceNumber() + 1L, dataSource);
        long head = sabrStream.getLiveHeadSequenceNumber();
        long targetDurationMs = manifest.liveSegmentTargetDurationSec * 1_000L;
        int segmentsPerResponse = liveSegmentsPerResponse;
        int maxPrefetchBytes = SabrLivePrefetcher.getMaxResponseBytes(
                trackSelection.getSelectedFormat().bitrate, targetDurationMs, segmentsPerResponse);

        for (int k = 1; k <= SabrLivePrefetcher.PREFETCH_COUNT; k++) {
            // A response holds the segments after the one asked for too (3 behind the head of a 1 s stream): the ones asked for ahead
            // are as far apart. One by one, each segment came in 3 responses, and 2 of them weren't read: 3x the bytes for as long as
            // the player stayed behind the head (2026-11-15).
            long offset = (long) k * segmentsPerResponse;
            long sequenceNumber = last.getSequenceNumber() + 1L + offset;

            if (head < 0 || sequenceNumber > head) {
                break; // a later one would be held until it exists
            }

            // As if the segment before it were the newest one received: its number, and its start about where it should be
            MediaHeader before = last.toBuilder()
                    .setSequenceNumber((int) (sequenceNumber - 1))
                    .setStartMs(last.getStartMs() + offset * targetDurationMs)
                    .build();
            DataSpec dataSpec = new DataSpec(
                    Uri.parse(manifest.getRequestUrl(trackType)),
                    DataSpec.HTTP_METHOD_POST,
                    manifest.createLiveRequestAfter(trackType, before).toByteArray(),
                    0, 0, C.LENGTH_UNSET, null, 0, sabrHeaders);
            prefetcher.prefetch(iTag, sequenceNumber, dataSpec, maxPrefetchBytes);
        }

        return chunkDataSource;
    }

    /**
     * The segments the last chunk brought: since the last chunk was made, one loaded (a track loads one at a time). One that brought
     * none (failed, or at the head) says nothing.
     */
    private void updateLiveSegmentsPerResponse(long lastSequenceNumber) {
        long previous = livePrefetchSequenceNumber;
        livePrefetchSequenceNumber = lastSequenceNumber;

        if (previous != -1 && lastSequenceNumber > previous) {
            liveSegmentsPerResponse = (int) Math.min(lastSequenceNumber - previous, SabrLivePrefetcher.MAX_SEGMENTS_PER_RESPONSE);
        }
    }

    private long getLastSequenceNumber(int itag) {
        MediaHeader mediaHeader = sabrStream != null ? sabrStream.getInitializedFormat(itag) : null;

        return mediaHeader != null && mediaHeader.hasSequenceNumber() ? mediaHeader.getSequenceNumber() : -1;
    }

    private void resetSampleDeduplicator() {
        if (sampleDeduplicator != null) {
            sampleDeduplicator.reset();
        }
    }

    private static boolean isEndpointConnectionFailure(Chunk chunk, Exception error) {
        if (!(error instanceof IOException) || error instanceof InvalidResponseCodeException) {
            return false;
        }

        // FIX: a media network that stalls in the middle of a response (read timeout) is as
        // unusable as one that doesn't answer at all. Retrying the same network keeps stalling.
        return chunk.bytesLoaded() == 0 || hasCause(error, SocketTimeoutException.class);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }

        return false;
    }

    private ArrayList<Representation> getRepresentations() {
        List<AdaptationSet> manifestAdaptationSets = manifest.getPeriod(periodIndex).adaptationSets;
        ArrayList<Representation> representations = new ArrayList<>();
        for (int adaptationSetIndex : adaptationSetIndices) {
            representations.addAll(manifestAdaptationSets.get(adaptationSetIndex).representations);
        }
        return representations;
    }

    private long resolveTimeToLiveEdgeUs(long playbackPositionUs) {
        boolean resolveTimeToLiveEdgePossible = manifest.dynamic && liveEdgeTimeUs != C.TIME_UNSET;
        return resolveTimeToLiveEdgePossible ? liveEdgeTimeUs - playbackPositionUs : C.TIME_UNSET;
    }

    private long getNowUnixTimeUs() {
        if (elapsedRealtimeOffsetMs != 0) {
            return (SystemClock.elapsedRealtime() + elapsedRealtimeOffsetMs) * 1000;
        } else {
            return System.currentTimeMillis() * 1000;
        }
    }

    //private long getSegmentNum(
    //        RepresentationHolder representationHolder,
    //        @Nullable MediaChunk previousChunk,
    //        long loadPositionUs,
    //        long firstAvailableSegmentNum,
    //        long lastAvailableSegmentNum) {
    //    return previousChunk != null
    //            ? previousChunk.getNextChunkIndex()
    //            : Util.constrainValue(
    //            representationHolder.getSegmentNum(loadPositionUs),
    //            firstAvailableSegmentNum,
    //            lastAvailableSegmentNum);
    //}

    //private void updateLiveEdgeTimeUs(
    //        RepresentationHolder representationHolder, long lastAvailableSegmentNum) {
    //    liveEdgeTimeUs = manifest.dynamic
    //            ? representationHolder.getSegmentEndTimeUs(lastAvailableSegmentNum) : C.TIME_UNSET;
    //}

    protected Chunk newInitializationChunk(
            RepresentationHolder representationHolder,
            DataSource dataSource,
            Format trackFormat,
            int trackSelectionReason,
            Object trackSelectionData,
            RangedUri initializationUri,
            RangedUri indexUri) {
        //RangedUri requestUri;
        //String baseUrl = representationHolder.representation.baseUrl;
        //baseUrl = Utils.updateQuery(baseUrl, "rn", sabrStream.getIncSabrRequestNumber());
        //if (initializationUri != null) {
        //    // It's common for initialization and index data to be stored adjacently. Attempt to merge
        //    // the two requests together to request both at once.
        //    requestUri = initializationUri.attemptMerge(indexUri, baseUrl);
        //    if (requestUri == null) {
        //        requestUri = initializationUri;
        //    }
        //} else {
        //    requestUri = indexUri;
        //}
        // NOTE: first protobuf request (before the video start off)
        // MOD: add protobuf data
        //DataSpec dataSpec = new DataSpec(requestUri.resolveUri(baseUrl), requestUri.start,
        //        requestUri.length, representationHolder.representation.getCacheKey());
        DataSpec dataSpec = new DataSpec(
                //Uri.parse(sabrStream.getRequestUrl()),
                Uri.parse(manifest.getRequestUrl(trackType)),
                DataSpec.HTTP_METHOD_POST,
                //sabrStream.createVideoPlaybackAbrRequest(trackType, true).toByteArray(),
                manifest.createVideoPlaybackAbrRequest(trackType, true).toByteArray(),
                0, 0, C.LENGTH_UNSET,
                //requestUri.start,
                //requestUri.start,
                //requestUri.length,
                representationHolder.representation.getCacheKey(),
                0,
                sabrHeaders);
        Log.e(TAG, "Load init chunk: track=" + trackType + ", rn=" + manifest.getSabrRequestNumber());

        if (sabrStream != null) {
            sabrStream.setInitRequest(true); // read up to the init segment only (see SabrExtractorInput)
        }

        return new InitializationChunk(dataSource, dataSpec, trackFormat,
                trackSelectionReason, trackSelectionData, representationHolder.extractorWrapper);
    }

    protected Chunk newMediaChunk(
            RepresentationHolder representationHolder,
            DataSource dataSource,
            int trackType,
            Format trackFormat,
            int trackSelectionReason,
            Object trackSelectionData,
            long firstSegmentNum,
            //int maxSegmentCount,
            long seekTimeUs) {
        Representation representation = representationHolder.representation;
        if (representationHolder.extractorWrapper == null) {
            DataSpec dataSpec = new DataSpec(
                    Uri.parse(representation.baseUrl), DataSpec.HTTP_METHOD_GET, null,
                    0, 0, C.LENGTH_UNSET, representation.getCacheKey(), 0,
                    manifest.visitorCookie != null
                            ? Collections.singletonMap("Cookie", manifest.visitorCookie)
                            : Collections.emptyMap());
            return new SingleSampleMediaChunk(
                    dataSource, dataSpec, trackFormat, trackSelectionReason, trackSelectionData,
                    0, representationHolder.periodDurationUs, 0, trackType, trackFormat);
        }

        SabrStream sabrStream = Assertions.checkNotNull(this.sabrStream);
        sabrStream.setInitRequest(false);
        // A canceled load's (its call aborted by the seek, "Socket closed", see CancelableCalls in the app): not the new chunk's, whose
        // completion would take it as its own, and leave the media network for a chunk that came empty
        sabrStream.pollResponseError();
        boolean isInit = nexChunkIdx == -1;
        FormatId formatId = formatSelector.getSelectedFormatId();
        int iTag = formatId != null ? formatId.getItag() : -1;

        // FIX: seek backwards does infinite loading after 60 second but the buffer is full
        boolean isSeek = seekTimeUs != C.TIME_UNSET; // same condition used for seekTimeUs
        if (isSeek) {
            sabrStream.reset(iTag);
            nexChunkIdx = -1; // or whatever "post-init" value newMediaChunk expects
        }

        // Old code
        //if (nexChunkIdx == -1) {
        //    sabrStream.reset(iTag);
        //}

        nexChunkIdx++;

        //long startTimeMs = sabrStream.getSegmentStartTimeMs(trackType);
        //long durationMs = sabrStream.getSegmentDurationMs(trackType);
        long startTimeMs = sabrStream.getSegmentStartTimeMs(iTag);
        long durationMs = sabrStream.getSegmentDurationMs(iTag);

        // FIX: no media segment of the format received yet (the init chunk stops at its init segment, see SabrExtractorInput):
        // the first media chunk is given a segment's length. Unset times overflow the buffered duration the player computes from
        // them, and it never loads the video again.
        if (durationMs <= 0) {
            durationMs = DEFAULT_SEGMENT_DURATION_MS;
        }

        boolean isSabrTimeSet = startTimeMs >= 0 && durationMs > 0;

        // The server's times are the media's: a live stream's timeline starts at its start segment's (see SabrManifestParser), a
        // video's at 0
        long presentationTimeOffsetUs = representation.presentationTimeOffsetUs;
        long startTimeUs = isSabrTimeSet ? startTimeMs * 1_000L - presentationTimeOffsetUs : C.TIME_UNSET;

        // FIX: of a live stream, with no segment of the format received since the seek (see SabrStream#reset): the time asked for,
        // not the media's 0, which is far before the timeline's start (a negative buffered duration)
        if (presentationTimeOffsetUs != 0 && !sabrStream.hasSegmentStartTime(iTag)) {
            long requestedTimeUs = seekTimeUs != C.TIME_UNSET ? seekTimeUs : pendingSeekTimeUs;
            startTimeUs = requestedTimeUs != C.TIME_UNSET ? requestedTimeUs : 0;
        }

        long endTimeUs = isSabrTimeSet ? startTimeUs + (durationMs * 1_000L) : C.TIME_UNSET;
        long clippedEndTimeUs = C.TIME_UNSET;
        int segmentCount = C.INDEX_UNSET; // TODO: replace with SABR value?
        //long startTimeUs = representationHolder.getSegmentStartTimeUs(firstSegmentNum);
        //RangedUri segmentUri = representationHolder.getSegmentUrl(firstSegmentNum);
        //String baseUrl = representation.baseUrl;
        //baseUrl = Utils.updateQuery(baseUrl, "rn", sabrStream.getIncSabrRequestNumber());

        // A live stream at its head: the next segment doesn't exist yet. The chunk is loaded now, its request waits: a chunk source
        // that gives none isn't asked again until a load ends (the player stalled). Also a seek retried after an answer with no media
        // (see getNextChunk): the seek's target may be the head's next segment.
        DataSource chunkDataSource = maybePrefetch(trackType, iTag, isSeek);
        if (nextLiveLoadTimeMs != C.TIME_UNSET) {
            if (chunkDataSource == dataSource) {
                chunkDataSource = new DelayedOpenDataSource(dataSource, nextLiveLoadTimeMs);
            }
            nextLiveLoadTimeMs = C.TIME_UNSET;
        }

        DataSpec dataSpec = new DataSpec(
                //Uri.parse(sabrStream.getRequestUrl()),
                Uri.parse(manifest.getRequestUrl(trackType)),
                //segmentUri.resolveUri(baseUrl),
                DataSpec.HTTP_METHOD_POST,
                //sabrStream.createVideoPlaybackAbrRequest(trackType, false).toByteArray(),
                manifest.createVideoPlaybackAbrRequest(trackType, false,
                        seekTimeUs != C.TIME_UNSET ? seekTimeUs + presentationTimeOffsetUs : C.TIME_UNSET).toByteArray(),
                0, 0, C.LENGTH_UNSET,
                //segmentUri.start,
                //segmentUri.start,
                //segmentUri.length,
                representation.getCacheKey(),
                0,
                sabrHeaders);
        if (manifest.isLive()) {
            liveRequestItag = iTag;
            liveRequestSequenceNumber = !isSeek && sabrStream.hasSegmentStartTime(iTag) ? getLastSequenceNumber(iTag) : -1;
        }

        long sampleOffsetUs = -presentationTimeOffsetUs;
        Log.e(TAG, "Load media chunk: track=" + trackType + ", rn=" + manifest.getSabrRequestNumber()
                + ", startTimeMs=" + startTimeMs + ", backoffTimeMs=" + sabrStream.getBackoffTimeMs());
        return new ContainerMediaChunk(
                chunkDataSource,
                dataSpec,
                trackFormat,
                trackSelectionReason,
                trackSelectionData,
                startTimeUs,
                endTimeUs,
                seekTimeUs,
                clippedEndTimeUs,
                firstSegmentNum,
                segmentCount,
                sampleOffsetUs,
                representationHolder.extractorWrapper) {
            @Override
            protected ChunkExtractorWrapper.TrackOutputProvider getTrackOutputProvider(BaseMediaChunkOutput output) {
                // FIX: a response that ends mid-segment makes the server send that segment again
                return sampleDeduplicator != null ? sampleDeduplicator.wrap(this, output) : output;
            }
        };

        //if (representationHolder.extractorWrapper == null) {
        //    long endTimeUs = representationHolder.getSegmentEndTimeUs(firstSegmentNum);
        //    DataSpec dataSpec = new DataSpec(segmentUri.resolveUri(baseUrl),
        //            segmentUri.start, segmentUri.length, representation.getCacheKey());
        //    return new SingleSampleMediaChunk(dataSource, dataSpec, trackFormat, trackSelectionReason,
        //            trackSelectionData, startTimeUs, endTimeUs, firstSegmentNum, trackType, trackFormat);
        //} else {
        //    int segmentCount = 1;
        //    for (int i = 1; i < maxSegmentCount; i++) {
        //        RangedUri nextSegmentUri = representationHolder.getSegmentUrl(firstSegmentNum + i);
        //        RangedUri mergedSegmentUri = segmentUri.attemptMerge(nextSegmentUri, baseUrl);
        //        if (mergedSegmentUri == null) {
        //            // Unable to merge segment fetches because the URIs do not merge.
        //            break;
        //        }
        //        segmentUri = mergedSegmentUri;
        //        segmentCount++;
        //    }
        //    long endTimeUs = representationHolder.getSegmentEndTimeUs(firstSegmentNum + segmentCount - 1);
        //    long periodDurationUs = representationHolder.periodDurationUs;
        //    long clippedEndTimeUs =
        //            periodDurationUs != C.TIME_UNSET && periodDurationUs <= endTimeUs
        //                    ? periodDurationUs
        //                    : C.TIME_UNSET;
        //    // NOTE: next protobuf requests (during the playback)
        //    // MOD: add protobuf data
        //    //DataSpec dataSpec = new DataSpec(segmentUri.resolveUri(baseUrl),
        //    //        segmentUri.start, segmentUri.length, representation.getCacheKey());
        //    int sabrRequestNumber = sabrStream.getIncSabrRequestNumber();
        //    DataSpec dataSpec = new DataSpec(
        //            segmentUri.resolveUri(baseUrl),
        //            DataSpec.HTTP_METHOD_POST,
        //            sabrStream.buildInitVideoPlaybackAbrRequest(trackType).toByteArray(),
        //            0, 0, C.LENGTH_UNSET,
        //            //segmentUri.start,
        //            //segmentUri.start,
        //            //segmentUri.length,
        //            representation.getCacheKey(),
        //            0,
        //            sabrHeaders);
        //    long sampleOffsetUs = -representation.presentationTimeOffsetUs;
        //    return new ContainerMediaChunk(
        //            dataSource,
        //            dataSpec,
        //            trackFormat,
        //            trackSelectionReason,
        //            trackSelectionData,
        //            startTimeUs,
        //            endTimeUs,
        //            seekTimeUs,
        //            clippedEndTimeUs,
        //            firstSegmentNum,
        //            segmentCount,
        //            sampleOffsetUs,
        //            representationHolder.extractorWrapper);
        //}
    }

    private static AudioSelector createAudioSelection(int trackType, TrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_AUDIO) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new AudioSelector("selected_audio", false, selectedFormat);
    }

    private static VideoSelector createVideoSelection(int trackType, TrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_VIDEO) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new VideoSelector("selected_video", false, selectedFormat);
    }

    private static CaptionSelector createCaptionSelection(int trackType, TrackSelection trackSelection) {
        if (trackType != C.TRACK_TYPE_TEXT) {
            return null;
        }

        Format selectedFormat = trackSelection.getSelectedFormat();

        return new CaptionSelector("selected_caption", false, selectedFormat);
    }

    private static FormatSelector createFormatSelector(int trackType, TrackSelection trackSelection) {
        Format selectedFormat = trackSelection.getSelectedFormat();

        switch (trackType) {
            case C.TRACK_TYPE_AUDIO:
                return new FormatSelector("selected_audio", false, selectedFormat);
            case C.TRACK_TYPE_VIDEO:
                return new FormatSelector("selected_video", false, selectedFormat);
            case C.TRACK_TYPE_TEXT:
                return new FormatSelector("selected_caption", false, selectedFormat);
        }

        throw new IllegalStateException("Unknown track type");
    }

    /** {@link MediaChunkIterator} wrapping a {@link RepresentationHolder}. */
    //protected static final class RepresentationSegmentIterator extends BaseMediaChunkIterator {
    //
    //    private final RepresentationHolder representationHolder;
    //
    //    /**
    //     * Creates iterator.
    //     *
    //     * @param representation The {@link RepresentationHolder} to wrap.
    //     * @param firstAvailableSegmentNum The number of the first available segment.
    //     * @param lastAvailableSegmentNum The number of the last available segment.
    //     */
    //    public RepresentationSegmentIterator(
    //            RepresentationHolder representation,
    //            long firstAvailableSegmentNum,
    //            long lastAvailableSegmentNum) {
    //        super(/* fromIndex= */ firstAvailableSegmentNum, /* toIndex= */ lastAvailableSegmentNum);
    //        this.representationHolder = representation;
    //    }
    //
    //    @Override
    //    public DataSpec getDataSpec() {
    //        checkInBounds();
    //        Representation representation = representationHolder.representation;
    //        RangedUri segmentUri = representationHolder.getSegmentUrl(getCurrentIndex());
    //        Uri resolvedUri = segmentUri.resolveUri(representation.baseUrl);
    //        String cacheKey = representation.getCacheKey();
    //        return new DataSpec(resolvedUri, segmentUri.start, segmentUri.length, cacheKey);
    //    }
    //
    //    @Override
    //    public long getChunkStartTimeUs() {
    //        checkInBounds();
    //        return representationHolder.getSegmentStartTimeUs(getCurrentIndex());
    //    }
    //
    //    @Override
    //    public long getChunkEndTimeUs() {
    //        checkInBounds();
    //        return representationHolder.getSegmentEndTimeUs(getCurrentIndex());
    //    }
    //}

    /**
     * Opens no earlier than a time (elapsed realtime): a live stream's request for a segment that doesn't exist yet (see
     * maybeWaitForLiveSegment). The wait ends early when the load is canceled (the loader interrupts its thread).
     */
    /* package */ static final class DelayedOpenDataSource implements DataSource {
        private final DataSource upstream;
        private final long openNotBeforeMs;

        DelayedOpenDataSource(DataSource upstream, long openNotBeforeMs) {
            this.upstream = upstream;
            this.openNotBeforeMs = openNotBeforeMs;
        }

        @Override
        public void addTransferListener(TransferListener transferListener) {
            upstream.addTransferListener(transferListener);
        }

        @Override
        public long open(DataSpec dataSpec) throws IOException {
            long waitMs = openNotBeforeMs - SystemClock.elapsedRealtime();

            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Canceled while waiting for the next live segment");
                }
            }

            return upstream.open(dataSpec);
        }

        @Override
        public int read(byte[] buffer, int offset, int readLength) throws IOException {
            return upstream.read(buffer, offset, readLength);
        }

        @Nullable
        @Override
        public Uri getUri() {
            return upstream.getUri();
        }

        @Override
        public Map<String, List<String>> getResponseHeaders() {
            return upstream.getResponseHeaders();
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }
    }

    /** Holds information about a snapshot of a single {@link Representation}. */
    protected static final class RepresentationHolder {

        /* package */ final @Nullable ChunkExtractorWrapper extractorWrapper;

        public final Representation representation;
        //public final @Nullable SabrSegmentIndex segmentIndex;

        private final long periodDurationUs;
        private final long segmentNumShift;

        /* package */ RepresentationHolder(
                long periodDurationUs,
                int trackType,
                Representation representation,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                TrackOutput playerEmsgTrackOutput,
                SabrStream sabrStream) {
            this(
                    periodDurationUs,
                    representation,
                    createExtractorWrapper(
                            trackType,
                            representation,
                            enableEventMessageTrack,
                            closedCaptionFormats,
                            playerEmsgTrackOutput,
                            sabrStream),
                    /* segmentNumShift= */ 0
                    //representation.getIndex()
            );
        }

        private RepresentationHolder(
                long periodDurationUs,
                Representation representation,
                @Nullable ChunkExtractorWrapper extractorWrapper,
                long segmentNumShift
                //@Nullable SabrSegmentIndex segmentIndex
        ) {
            this.periodDurationUs = periodDurationUs;
            this.representation = representation;
            this.segmentNumShift = segmentNumShift;
            this.extractorWrapper = extractorWrapper;
            //this.segmentIndex = segmentIndex;
        }

        @CheckResult
            /* package */ RepresentationHolder copyWithNewRepresentation(
                long newPeriodDurationUs, Representation newRepresentation)
                throws BehindLiveWindowException {

            return new RepresentationHolder(
                    newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift);

            //SabrSegmentIndex oldIndex = representation.getIndex();
            //SabrSegmentIndex newIndex = newRepresentation.getIndex();

            //if (oldIndex == null) {
            //    // Segment numbers cannot shift if the index isn't defined by the manifest.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, oldIndex);
            //}
            //
            //if (!oldIndex.isExplicit()) {
            //    // Segment numbers cannot shift if the index isn't explicit.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, newIndex);
            //}
            //
            //int oldIndexSegmentCount = oldIndex.getSegmentCount(newPeriodDurationUs);
            //if (oldIndexSegmentCount == 0) {
            //    // Segment numbers cannot shift if the old index was empty.
            //    return new RepresentationHolder(
            //            newPeriodDurationUs, newRepresentation, extractorWrapper, segmentNumShift, newIndex);
            //}
            //
            //long oldIndexFirstSegmentNum = oldIndex.getFirstSegmentNum();
            //long oldIndexStartTimeUs = oldIndex.getTimeUs(oldIndexFirstSegmentNum);
            //long oldIndexLastSegmentNum = oldIndexFirstSegmentNum + oldIndexSegmentCount - 1;
            //long oldIndexEndTimeUs =
            //        oldIndex.getTimeUs(oldIndexLastSegmentNum)
            //                + oldIndex.getDurationUs(oldIndexLastSegmentNum, newPeriodDurationUs);
            //long newIndexFirstSegmentNum = newIndex.getFirstSegmentNum();
            //long newIndexStartTimeUs = newIndex.getTimeUs(newIndexFirstSegmentNum);
            //long newSegmentNumShift = segmentNumShift;
            //if (oldIndexEndTimeUs == newIndexStartTimeUs) {
            //    // The new index continues where the old one ended, with no overlap.
            //    newSegmentNumShift += oldIndexLastSegmentNum + 1 - newIndexFirstSegmentNum;
            //} else if (oldIndexEndTimeUs < newIndexStartTimeUs) {
            //    // There's a gap between the old index and the new one which means we've slipped behind the
            //    // live window and can't proceed.
            //    throw new BehindLiveWindowException();
            //} else if (newIndexStartTimeUs < oldIndexStartTimeUs) {
            //    // The new index overlaps with (but does not have a start position contained within) the old
            //    // index. This can only happen if extra segments have been added to the start of the index.
            //    newSegmentNumShift -=
            //            newIndex.getSegmentNum(oldIndexStartTimeUs, newPeriodDurationUs)
            //                    - oldIndexFirstSegmentNum;
            //} else {
            //    // The new index overlaps with (and has a start position contained within) the old index.
            //    newSegmentNumShift +=
            //            oldIndex.getSegmentNum(newIndexStartTimeUs, newPeriodDurationUs)
            //                    - newIndexFirstSegmentNum;
            //}
            //return new RepresentationHolder(
            //        newPeriodDurationUs, newRepresentation, extractorWrapper, newSegmentNumShift, newIndex);
        }

        //@CheckResult
        //    /* package */ RepresentationHolder copyWithNewSegmentIndex(SabrSegmentIndex segmentIndex) {
        //    return new RepresentationHolder(
        //            periodDurationUs, representation, extractorWrapper, segmentNumShift, segmentIndex);
        //}

        //public long getFirstSegmentNum() {
        //    return segmentIndex.getFirstSegmentNum() + segmentNumShift;
        //}
        //
        //public int getSegmentCount() {
        //    return segmentIndex.getSegmentCount(periodDurationUs);
        //}
        //
        //public long getSegmentStartTimeUs(long segmentNum) {
        //    return segmentIndex.getTimeUs(segmentNum - segmentNumShift);
        //}
        //
        //public long getSegmentEndTimeUs(long segmentNum) {
        //    return getSegmentStartTimeUs(segmentNum)
        //            + segmentIndex.getDurationUs(segmentNum - segmentNumShift, periodDurationUs);
        //}
        //
        //public long getSegmentNum(long positionUs) {
        //    return segmentIndex.getSegmentNum(positionUs, periodDurationUs) + segmentNumShift;
        //}
        //
        //public RangedUri getSegmentUrl(long segmentNum) {
        //    return segmentIndex.getSegmentUrl(segmentNum - segmentNumShift);
        //}

        //public long getFirstAvailableSegmentNum(
        //        SabrManifest manifest, int periodIndex, long nowUnixTimeUs) {
        //    if (getSegmentCount() == SabrSegmentIndex.INDEX_UNBOUNDED
        //            && manifest.timeShiftBufferDepthMs != C.TIME_UNSET) {
        //        // The index is itself unbounded. We need to use the current time to calculate the range of
        //        // available segments.
        //        long liveEdgeTimeUs = nowUnixTimeUs - C.msToUs(manifest.availabilityStartTimeMs);
        //        long periodStartUs = C.msToUs(manifest.getPeriod(periodIndex).startMs);
        //        long liveEdgeTimeInPeriodUs = liveEdgeTimeUs - periodStartUs;
        //        long bufferDepthUs = C.msToUs(manifest.timeShiftBufferDepthMs);
        //        return Math.max(
        //                getFirstSegmentNum(), getSegmentNum(liveEdgeTimeInPeriodUs - bufferDepthUs));
        //    }
        //    return getFirstSegmentNum();
        //}
        //
        //public long getLastAvailableSegmentNum(
        //        SabrManifest manifest, int periodIndex, long nowUnixTimeUs) {
        //    int availableSegmentCount = getSegmentCount();
        //    if (availableSegmentCount == SabrSegmentIndex.INDEX_UNBOUNDED) {
        //        // The index is itself unbounded. We need to use the current time to calculate the range of
        //        // available segments.
        //        long liveEdgeTimeUs = nowUnixTimeUs - C.msToUs(manifest.availabilityStartTimeMs);
        //        long periodStartUs = C.msToUs(manifest.getPeriod(periodIndex).startMs);
        //        long liveEdgeTimeInPeriodUs = liveEdgeTimeUs - periodStartUs;
        //        // getSegmentNum(liveEdgeTimeInPeriodUs) will not be completed yet, so subtract one to get
        //        // the index of the last completed segment.
        //        return getSegmentNum(liveEdgeTimeInPeriodUs) - 1;
        //    }
        //    return getFirstSegmentNum() + availableSegmentCount - 1;
        //}

        private static boolean mimeTypeIsWebm(String mimeType) {
            return mimeType.startsWith(MimeTypes.VIDEO_WEBM) || mimeType.startsWith(MimeTypes.AUDIO_WEBM)
                    || mimeType.startsWith(MimeTypes.APPLICATION_WEBM);
        }

        private static boolean mimeTypeIsRawText(String mimeType) {
            return MimeTypes.isText(mimeType) || MimeTypes.APPLICATION_TTML.equals(mimeType);
        }

        private static boolean needsExtractor(Representation representation) {
            String containerMimeType = representation.format.containerMimeType;
            return containerMimeType != null && !mimeTypeIsRawText(containerMimeType);
        }

        private static @Nullable ChunkExtractorWrapper createExtractorWrapper(
                int trackType,
                Representation representation,
                boolean enableEventMessageTrack,
                List<Format> closedCaptionFormats,
                TrackOutput playerEmsgTrackOutput,
                SabrStream sabrStream) {
            String containerMimeType = representation.format.containerMimeType;
            if (!needsExtractor(representation)) {
                return null;
            }

            // MOD: replaced with SabrExtractor
            //Extractor extractor;
            //if (MimeTypes.APPLICATION_RAWCC.equals(containerMimeType)) {
            //    extractor = new RawCcExtractor(representation.format);
            //} else if (mimeTypeIsWebm(containerMimeType)) {
            //    extractor = new MatroskaExtractor(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES);
            //} else {
            //    int flags = 0;
            //    if (enableEventMessageTrack) {
            //        flags |= FragmentedMp4Extractor.FLAG_ENABLE_EMSG_TRACK;
            //    }
            //    extractor =
            //            new FragmentedMp4Extractor(
            //                    flags, null, null, null, closedCaptionFormats, playerEmsgTrackOutput);
            //}

            //Extractor extractor = new SabrExtractor(trackType, representation.format, sabrStream);

            Extractor extractor;
            if (MimeTypes.APPLICATION_RAWCC.equals(containerMimeType)) {
                extractor = new RawCcExtractor(representation.format);
            } else if (mimeTypeIsWebm(containerMimeType)) {
                extractor = new SabrMatroskaAdapter(SabrMatroskaAdapter.FLAG_DISABLE_SEEK_FOR_CUES, sabrStream);
            } else {
                int flags = 0;
                if (enableEventMessageTrack) {
                    flags |= SabrFragmentedMp4Adapter.FLAG_ENABLE_EMSG_TRACK;
                }
                extractor =
                        new SabrFragmentedMp4Adapter(
                                flags, null, null, null, closedCaptionFormats, playerEmsgTrackOutput, sabrStream);
            }

            // Prefer drmInitData obtained from the manifest over drmInitData obtained from the stream,
            // as per DASH IF Interoperability Recommendations V3.0, 7.5.3.
            return new ChunkExtractorWrapper(extractor, trackType, representation.format);
        }
    }
}
