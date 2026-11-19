package com.google.android.exoplayer2.source.sabr.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.BrokenPartError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.BrokenResponseError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.MediaSegmentMismatchError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrProtocolError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrStreamError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.UnknownPartError;
import com.google.android.exoplayer2.source.sabr.parser.models.FormatSelector;
import com.google.android.exoplayer2.source.sabr.parser.parts.FormatInitializedSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSeekSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSegmentDataSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSegmentEndSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSegmentInitSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.PoTokenStatusSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.PoTokenStatusSabrPart.PoTokenStatus;
import com.google.android.exoplayer2.source.sabr.parser.parts.RefreshPlayerResponseSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.SabrPart;
import com.google.android.exoplayer2.source.sabr.parser.results.ProcessFormatInitializationMetadataResult;
import com.google.android.exoplayer2.source.sabr.parser.results.ProcessMediaEndResult;
import com.google.android.exoplayer2.source.sabr.parser.results.ProcessMediaHeaderResult;
import com.google.android.exoplayer2.source.sabr.parser.results.ProcessMediaResult;
import com.google.android.exoplayer2.source.sabr.parser.results.ProcessStreamProtectionStatusResult;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPDecoder;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPart;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPartId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.FormatInitializationMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.LiveMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.MediaHeader;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.NextRequestPolicy;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.ReloadPlayerResponse;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrContextSendingPolicy;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrContextUpdate;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrError;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrRedirect;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrSeek;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamProtectionStatus;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.upstream.HttpDataSource.HttpDataSourceException;
import com.google.protobuf.InvalidProtocolBufferException;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.querystringparser.UrlQueryString;
import com.liskovsoft.sharedutils.querystringparser.UrlQueryStringFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SabrStream {
    private static final String TAG = SabrStream.class.getSimpleName();
    /**
     * Media responses in a row with no media while the poToken's attestation was pending: the server is withholding it (see
     * #onResponseEnd). Fewer is a response with none for another reason, e.g. a redirect.
     */
    private static final int MAX_PENDING_RESPONSES_WITHOUT_MEDIA = 3;
    /**
     * Of an unknown part, the bytes after its header told (see #peekHex)
     */
    private static final int PEEKED_BYTES = 16;
    /**
     * How many of the parts read last a broken response tells (see #getPartTrail)
     */
    private static final int PART_TRAIL_LENGTH = 8;
    private final int[] KNOWN_PARTS = {
            UMPPartId.MEDIA_HEADER,
            UMPPartId.MEDIA,
            UMPPartId.MEDIA_END,
            UMPPartId.STREAM_PROTECTION_STATUS,
            UMPPartId.SABR_REDIRECT,
            UMPPartId.FORMAT_INITIALIZATION_METADATA,
            UMPPartId.NEXT_REQUEST_POLICY,
            UMPPartId.LIVE_METADATA, // a live stream's head and seekable range, in each response
            UMPPartId.SABR_SEEK, // e.g. to a live stream's head, when the time asked for is out of its range
            UMPPartId.SABR_ERROR,
            UMPPartId.SABR_CONTEXT_UPDATE,
            UMPPartId.SABR_CONTEXT_SENDING_POLICY,
            UMPPartId.RELOAD_PLAYER_RESPONSE,
            //UMPPartId.SNACKBAR_MESSAGE // ???
    };
    private final int[] IGNORED_PARTS = {
            UMPPartId.REQUEST_IDENTIFIER,
            UMPPartId.REQUEST_CANCELLATION_POLICY,
            UMPPartId.PLAYBACK_START_POLICY,
            UMPPartId.ALLOWED_CACHED_FORMATS,
            UMPPartId.PAUSE_BW_SAMPLING_HINT,
            UMPPartId.START_BW_SAMPLING_HINT,
            UMPPartId.REQUEST_PIPELINING,
            UMPPartId.SELECTABLE_FORMATS,
            UMPPartId.PREWARM_CONNECTION,
    };
    private final UMPDecoder decoder;
    private final SabrProcessor processor;
    private final NoSegmentsTracker noNewSegmentsTracker;
    private final Set<Integer> unknownPartTypes;
    private int sqMismatchForwardCount;
    private int sqMismatchBacktrackCount;
    private boolean receivedNewSegments;
    private String url;
    private List<? extends  SabrPart> multiResult = null;
    private int segmentItag = -1;
    private long segmentSequenceNumber = -1;
    /**
     * The request being read is the init chunk's (see DefaultSabrChunkSource#newInitializationChunk): its response ends with the
     * init segment (see SabrExtractorInput)
     */
    private volatile boolean isInitRequest;
    @Nullable private volatile IOException responseError;
    @Nullable private static volatile BackoffListener backoffListener;
    @Nullable private static volatile ProtectionStatusListener protectionStatusListener;
    @Nullable private static volatile RedirectListener redirectListener;
    @Nullable private final String videoId;
    /**
     * The server's last word on the poToken, which each response has (see ProtectionStatusListener), null before the first
     */
    @Nullable private volatile PoTokenStatus protectionStatus;
    /**
     * Media responses in a row that came with no media while the attestation was pending (see #onResponseEnd)
     */
    private int pendingResponsesWithoutMedia;
    /**
     * The NextRequestPolicy parts read so far: one read since a request was made came in its answer (see #getBackoffTimeMs, which is
     * the last one's)
     */
    private volatile int nextRequestPolicyCount;
    /**
     * The parts the response being read gave, the last PART_TRAIL_LENGTH of them (see #getPartTrail). On the loader's thread, as the
     * parts are read.
     */
    private final int[] partTrailIds = new int[PART_TRAIL_LENGTH];
    private final int[] partTrailSizes = new int[PART_TRAIL_LENGTH];
    private int partTrailCount;
    /**
     * Where the last part's header ended in its response, -1 before the first
     */
    private long partTrailPosition = -1;

    /**
     * Told each wait the server asks for (see NextRequestPolicy#getBackoffTimeMs), e.g. for the app's timing of video starts: one
     * under SabrExtractorInput's MIN_SWALLOW_BACKOFF_MS isn't waited, unless an init request's answer had no init segment (see
     * DefaultSabrChunkSource#maybeWaitForInit). On the loader's thread.
     */
    public interface BackoffListener {
        void onBackoff(int backoffMs);
    }

    public static void setBackoffListener(@Nullable BackoffListener listener) {
        backoffListener = listener;
    }

    /**
     * Told what the server makes of the poToken (see SabrProcessor#processStreamProtectionStatus), e.g. for the app's diagnostics:
     * a stream's first status, then each change. On the loader's thread.
     */
    public interface ProtectionStatusListener {
        void onProtectionStatus(@Nullable String videoId, PoTokenStatus status);
    }

    public static void setProtectionStatusListener(@Nullable ProtectionStatusListener listener) {
        protectionStatusListener = listener;
    }

    /**
     * Told the URL the server sends the stream to from now on (see SabrRedirect), e.g. for the app to connect to its host ahead of the
     * next request, which otherwise opens a connection of its own. On the loader's thread, while the response that said so is read.
     */
    public interface RedirectListener {
        /**
         * @param fromUrl the stream's URL before, null for none
         */
        void onRedirect(@Nullable String fromUrl, String toUrl);
    }

    public static void setRedirectListener(@Nullable RedirectListener listener) {
        redirectListener = listener;
    }

    private static class NoSegmentsTracker {
        public int consecutiveRequests = 0;
        public float timestampStarted = -1;
        public int liveHeadSegmentStarted = -1;

        public void reset() {
             consecutiveRequests = 0;
             timestampStarted = -1;
             liveHeadSegmentStarted = -1;
        }

        public void increment(int liveHeadSegment) {
            if (consecutiveRequests == 0) {
                timestampStarted = System.currentTimeMillis() * 1_000;
                liveHeadSegmentStarted = liveHeadSegment;
            }
            consecutiveRequests += 1;
        }
    }

    public SabrStream(
            @NonNull String serverAbrStreamingUrl,
            @NonNull String videoPlaybackUstreamerConfig,
            @NonNull ClientInfo clientInfo,
            int liveSegmentTargetDurationSec,
            int liveSegmentTargetDurationToleranceMs,
            long startTimeMs,
            String poToken,
            boolean postLive,
            String videoId,
            long durationMs) {
        decoder = new UMPDecoder();
        processor = new SabrProcessor(
                videoPlaybackUstreamerConfig,
                clientInfo,
                liveSegmentTargetDurationSec,
                liveSegmentTargetDurationToleranceMs,
                startTimeMs,
                poToken,
                postLive,
                videoId,
                durationMs
        );
        url = serverAbrStreamingUrl;
        this.videoId = videoId;

        // Whether we got any new (not consumed) segments in the request
        noNewSegmentsTracker = new NoSegmentsTracker();
        unknownPartTypes = new HashSet<>();

        sqMismatchBacktrackCount = 0;
        sqMismatchForwardCount = 0;
    }


    public SabrPart parse(@NonNull ExtractorInput extractorInput) {
        SabrPart result = null;

        try {
            while (result == null && (multiResult == null || multiResult.isEmpty())) {
                UMPPart part = nextKnownUMPPart(extractorInput);

                if (part == null) {
                    break;
                }

                result = parsePart(part);

                if (result == null) {
                    multiResult = parseMultiPart(part);
                }
            }
        } catch (BrokenResponseError e) {
            // Where in the response it went wrong, told with the error: a first part is a response that isn't UMP, a later one a read
            // that went out of step
            e.setPartTrail(getPartTrail());

            throw e;
        }

        if (result instanceof MediaSegmentDataSabrPart) {
            MediaSegmentDataSabrPart data = (MediaSegmentDataSabrPart) result;
            segmentItag = data.formatId.getItag();
            segmentSequenceNumber = data.sequenceNumber;
        }

        return result != null ? result : multiResult != null && !multiResult.isEmpty() ? multiResult.remove(0) : null;
    }

    /**
     * Itag of the segment whose media is being read, -1 before the first one.
     */
    public int getSegmentItag() {
        return segmentItag;
    }

    /**
     * Sequence number of the segment whose media is being read, -1 before the first one or for an init segment.
     */
    public long getSegmentSequenceNumber() {
        return segmentSequenceNumber;
    }

    /**
     * The connection broke in the middle of a response, which ended early with the media it had.
     */
    public void setResponseError(IOException error) {
        responseError = error;
    }

    /**
     * The connection error that ended a response early since the last call, null if none did.
     */
    @Nullable
    public IOException pollResponseError() {
        IOException error = responseError;
        responseError = null;
        return error;
    }

    /**
     * The data source failed on the connection: an I/O error under it. An answer of the server (a response code, a content type)
     * has none, and an interrupted read is a canceled load. SABR wraps it in an IllegalStateException (see UMPDecoder).
     */
    @Nullable
    public static IOException getConnectionError(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpDataSourceException) {
                Throwable ioError = cause.getCause();
                boolean canceled = ioError instanceof InterruptedIOException && !(ioError instanceof SocketTimeoutException);
                return ioError instanceof IOException && !canceled ? (IOException) cause : null;
            }
        }

        return null;
    }

    public void reset() {
        noNewSegmentsTracker.reset();
    }

    /**
     * A live stream's media headers have no duration: it's estimated from the target duration (see SabrProcessor#processMediaHeader).
     * Known from the format info before the first response, whose live metadata says it too.
     */
    public void setLive(boolean isLive) {
        processor.setLive(isLive);
    }

    /**
     * Of the streams fetched again (see SabrManifest#refreshSession, #requestSessionRefresh): the requests from now on send it
     */
    public void setPoToken(@Nullable String poToken) {
        processor.setPoToken(poToken);
    }

    public boolean isLive() {
        return processor.isLive();
    }

    /**
     * Of a live stream, the newest segment the server has (see LiveMetadata), -1 before it says
     */
    public long getLiveHeadSequenceNumber() {
        return processor.getLiveHeadSequenceNumber();
    }

    public void reset(int iTag) {
        processor.reset(iTag);
    }

    public FormatSelector getFormatSelector() {
        return processor.getFormatSelector();
    }

    public void setFormatSelector(FormatSelector formatSelector) {
        processor.setFormatSelector(formatSelector);
    }

    public long getSegmentStartTimeMs(int iTag) {
        return processor.getSegmentStartTimeMs(iTag);
    }

    /**
     * How far below the video's total duration (in ms) a seek target should be clamped when it
     * would otherwise land at/past the end (see DefaultSabrChunkSource.getAdjustedSeekPositionUs()).
     * Based on the same live-segment-target-duration estimate SabrProcessor uses for a segment's own duration.
     * <p/>
     * Currently unused - the fixed constant in DefaultSabrChunkSource was consistently smaller
     * (safer) on tested content. Kept for future use.
     */
    public long getEndOfStreamSeekToleranceMs() {
        return processor.getLiveSegmentTargetDurationSec() * 1_000L
                - processor.getLiveSegmentTargetDurationToleranceMs();
    }

    public boolean hasSegmentStartTime(int iTag) {
        return processor.hasSegmentStartTime(iTag);
    }

    public long getSegmentDurationMs(int iTag) {
        return processor.getSegmentDurationMs(iTag);
    }

    public void setInitRequest(boolean isInitRequest) {
        this.isInitRequest = isInitRequest;
    }

    public boolean isInitRequest() {
        return isInitRequest;
    }

    public int getBackoffTimeMs() {
        return processor.getBackoffTimeMs();
    }

    /**
     * The NextRequestPolicy parts read so far: compared with the count when a request was made, one came in its answer
     */
    public int getNextRequestPolicyCount() {
        return nextRequestPolicyCount;
    }

    public MediaHeader getInitializedFormat(int iTag) {
        return processor.getInitializedFormats().get(iTag);
    }

    public StreamerContext createStreamerContext() {
        return processor.createStreamerContext();
    }

    private SabrPart parsePart(UMPPart part) {
        switch (part.partId) {
            case UMPPartId.MEDIA_HEADER:
                return processMediaHeader(part);
            case UMPPartId.MEDIA:
                return processMedia(part);
            case UMPPartId.MEDIA_END:
                return processMediaEnd(part);
            case UMPPartId.STREAM_PROTECTION_STATUS:
                return processStreamProtectionStatus(part);
            case UMPPartId.SABR_REDIRECT:
                processSabrRedirect(part);
                return null;
            case UMPPartId.FORMAT_INITIALIZATION_METADATA:
                return processFormatInitializationMetadata(part);
            case UMPPartId.NEXT_REQUEST_POLICY:
                processNextRequestPolicy(part);
                return null;
            case UMPPartId.SABR_ERROR:
                processSabrError(part);
                return null;
            case UMPPartId.SABR_CONTEXT_UPDATE:
                processSabrContextUpdate(part);
                return null;
            case UMPPartId.SABR_CONTEXT_SENDING_POLICY:
                processSabrContextSendingPolicy(part);
                return null;
            case UMPPartId.RELOAD_PLAYER_RESPONSE:
                return processReloadPlayerResponse(part);
            case UMPPartId.LIVE_METADATA:
            case UMPPartId.SABR_SEEK:
                return null; // parts of their own (see parseMultiPart)
        }

        if (!contains(IGNORED_PARTS, part.partId)) {
            unknownPartTypes.add(part.partId);
        }

        Log.d(TAG, "Unhandled part type %s", part.partId);

        return null;
    }

    private List<? extends SabrPart> parseMultiPart(UMPPart part) {
        switch (part.partId) {
            case UMPPartId.LIVE_METADATA:
                return processLiveMetadata(part);
            case UMPPartId.SABR_SEEK:
                return processSabrSeek(part);
        }

        return null;
    }

    private MediaSegmentInitSabrPart processMediaHeader(UMPPart part) {
        MediaHeader mediaHeader;

        try {
            mediaHeader = MediaHeader.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        try {
            ProcessMediaHeaderResult result = processor.processMediaHeader(mediaHeader);

            return result.sabrPart;
        } catch (MediaSegmentMismatchError e) {
            // For livestreams, the server may not know the exact segment for a given player time.
            // For segments near stream head, it estimates using segment duration, which can cause off-by-one segment mismatches.
            // If a segment is much longer or shorter than expected, the server may return a segment ahead or behind.
            // In such cases, retry with an adjusted player time to resync.
            if (processor.isLive() && e.receivedSequenceNumber == e.expectedSequenceNumber - 1) {
                // The segment before the previous segment was possibly longer than expected.
                // Move the player time forward to try to adjust for this.;
                processor.setPlayerTimeMs(processor.getPlayerTimeMs() + processor.getLiveSegmentTargetDurationToleranceMs());
                sqMismatchForwardCount += 1;
                return null;
            } else if (processor.isLive() && e.receivedSequenceNumber == e.expectedSequenceNumber + 2) {
                // The previous segment was possibly shorter than expected
                // Move the player time backwards to try to adjust for this.
                processor.setPlayerTimeMs(Math.max(0, processor.getPlayerTimeMs() - processor.getLiveSegmentTargetDurationToleranceMs()));
                sqMismatchBacktrackCount += 1;
                return null;
            }

            throw e;
        }
    }

    private MediaSegmentDataSabrPart processMedia(UMPPart part) {
        try {
            long position = part.data.getPosition();
            long headerId = decoder.readVarInt(part.data);
            long offset = part.data.getPosition() - position;
            int contentLength = part.size - (int) offset;

            ProcessMediaResult result = processor.processMedia(headerId, contentLength, part.data);

            return result.sabrPart;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private MediaSegmentEndSabrPart processMediaEnd(UMPPart part) {
        try {
            long headerId = decoder.readVarInt(part.data);
            Log.d(TAG, "Header ID: %s", headerId);

            ProcessMediaEndResult result = processor.processMediaEnd(headerId);

            if (result.isNewSegment) {
                receivedNewSegments = true;
            }

            return result.sabrPart;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private PoTokenStatusSabrPart processStreamProtectionStatus(UMPPart part) {
        StreamProtectionStatus sps;

        try {
            sps = StreamProtectionStatus.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process StreamProtectionStatus: %s", sps);
        ProcessStreamProtectionStatusResult result = processor.processStreamProtectionStatus(sps);
        PoTokenStatusSabrPart sabrPart = result.sabrPart;
        ProtectionStatusListener listener = protectionStatusListener;

        // Each response has it: told once, then when it changes
        if (sabrPart != null && sabrPart.status != protectionStatus) {
            protectionStatus = sabrPart.status;

            if (listener != null) {
                listener.onProtectionStatus(videoId, sabrPart.status);
            }
        }

        return sabrPart;
    }

    /**
     * A media request's response was read to its end (see SabrExtractorInput). With the poToken's attestation pending, the server
     * sends a minute of the video (its grace period), then answers each request at once with no media: the player stalled at
     * 0:59 and the same client's reload froze there (51AQC7FS, BQ0MY38T). A live stream's response has none while the
     * segment asked for doesn't exist yet (see DefaultSabrChunkSource#maybeWaitForLiveSegment).
     *
     * @param hasMedia a media segment came in it
     * @return the poToken's status when the server withholds the media for it: pending, with no media in the last
     * MAX_PENDING_RESPONSES_WITHOUT_MEDIA responses. Null otherwise.
     */
    @Nullable
    public PoTokenStatus onResponseEnd(boolean hasMedia) {
        PoTokenStatus status = protectionStatus;
        boolean isPending = status == PoTokenStatus.PENDING || status == PoTokenStatus.PENDING_MISSING;

        if (hasMedia || !isPending || isLive()) {
            pendingResponsesWithoutMedia = 0;
            return null;
        }

        pendingResponsesWithoutMedia++;

        return pendingResponsesWithoutMedia >= MAX_PENDING_RESPONSES_WITHOUT_MEDIA ? status : null;
    }

    private void processSabrRedirect(UMPPart part) {
        SabrRedirect sabrRedirect;

        try {
            sabrRedirect = SabrRedirect.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process SabrRedirect: %s", sabrRedirect);

        if (!sabrRedirect.hasRedirectUrl()) {
            Log.d(TAG, "Server requested to redirect to an invalid URL");
            return;
        }

        String fromUrl = this.url;
        setUrl(sabrRedirect.getRedirectUrl());
        RedirectListener listener = redirectListener;

        if (listener != null) {
            try {
                listener.onRedirect(fromUrl, this.url);
            } catch (RuntimeException e) {
                // The stream goes on without it
                Log.e(TAG, "Redirect listener failed: %s", e.getMessage());
            }
        }
    }

    private FormatInitializedSabrPart processFormatInitializationMetadata(UMPPart part) {
        FormatInitializationMetadata fmtInitMetadata;

        try {
            fmtInitMetadata = FormatInitializationMetadata.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process FormatInitializationMetadata: %s", fmtInitMetadata);
        ProcessFormatInitializationMetadataResult result = processor.processFormatInitializationMetadata(fmtInitMetadata);

        return result.sabrPart;
    }

    private void processNextRequestPolicy(UMPPart part) {
        NextRequestPolicy nextRequestPolicy;

        try {
            nextRequestPolicy = NextRequestPolicy.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process NextRequestPolicy: %s", nextRequestPolicy);
        processor.processNextRequestPolicy(nextRequestPolicy);
        nextRequestPolicyCount++;
        BackoffListener listener = backoffListener;

        if (listener != null && nextRequestPolicy.getBackoffTimeMs() > 0) {
            listener.onBackoff(nextRequestPolicy.getBackoffTimeMs());
        }
    }

    private void processSabrError(UMPPart part) {
        SabrError sabrError;

        try {
            sabrError = SabrError.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process SabrError: %s", sabrError);
        throw new SabrProtocolError(sabrError);
    }

    private void processSabrContextUpdate(UMPPart part) {
        SabrContextUpdate sabrCtxUpdate;

        try {
            sabrCtxUpdate = SabrContextUpdate.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process SabrContextUpdate: %s", sabrCtxUpdate);
        processor.processSabrContextUpdate(sabrCtxUpdate);
    }

    private void processSabrContextSendingPolicy(UMPPart part) {
        SabrContextSendingPolicy sabrCtxSendingPolicy;

        try {
            sabrCtxSendingPolicy = SabrContextSendingPolicy.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process SabrContextSendingPolicy: %s", sabrCtxSendingPolicy);
        processor.processSabrContextSendingPolicy(sabrCtxSendingPolicy);
    }

    /**
     * Request to reload initial player info with the token.<br/>
     * Player info url: <b>www.youtube.com/youtubei/v1/player</b><br/>
     * <pre>
     *    "playbackContext": {
     *        "reloadPlaybackContext": {
     *            "reloadPlaybackParams": {
     *                "token": reload_playback_token
     *            }
     *        }
     *    }
     * </pre>
     */
    private RefreshPlayerResponseSabrPart processReloadPlayerResponse(UMPPart part) {
        ReloadPlayerResponse reloadPlayerResponse;

        try {
            reloadPlayerResponse = ReloadPlayerResponse.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process ReloadPlayerResponse: %s", reloadPlayerResponse);
        return new RefreshPlayerResponseSabrPart(
                RefreshPlayerResponseSabrPart.Reason.SABR_RELOAD_PLAYER_RESPONSE,
                reloadPlayerResponse.hasReloadPlaybackParams() && reloadPlayerResponse.getReloadPlaybackParams().hasToken()
                        ? reloadPlayerResponse.getReloadPlaybackParams().getToken() : null
        );
    }

    private List<MediaSeekSabrPart> processLiveMetadata(UMPPart part) {
        LiveMetadata liveMetadata;

        try {
            liveMetadata = LiveMetadata.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process LiveMetadata: %s", liveMetadata);
        return processor.processLiveMetadata(liveMetadata).seekSabrParts;
    }

    private List<MediaSeekSabrPart> processSabrSeek(UMPPart part) {
        SabrSeek sabrSeek;

        try {
            sabrSeek = SabrSeek.parseFrom(part.toStream());
        } catch (IOException e) {
            throw partError(e);
        }

        Log.d(TAG, "Process SabrSeek: %s", sabrSeek);
        return processor.processSabrSeek(sabrSeek).seekSabrParts;
    }

    private static boolean contains(int[] array, int value) {
        for (int num : array) {
            if (num == value) {
                return true;
            }
        }
        return false;
    }

    private UMPPart nextKnownUMPPart(@NonNull ExtractorInput extractorInput) {
        UMPPart part;

        while (true) {
            part = decoder.decode(extractorInput);

            if (part == null) {
                Log.d(TAG, "The UMP stream is ended.");
                break;
            }

            rememberPart(part);

            // Normal reading: 47, 58. 52, 53, 42, 35, 20, 21, 22, 20...
            if (contains(KNOWN_PARTS, part.partId)) {
                Log.d(TAG, "Found known part: id=%s, size=%s, position=%s", part.partId, part.size, part.data.getPosition());
                break;
            } else {
                String msg = String.format("Unknown part encountered: id=%s, size=%s, position=%s", part.partId, part.size, part.data.getPosition());
                if (part.partId > 100) {
                    // With what came after its header: a UMP part's, or another kind of response's (e.g. a page)
                    throw new UnknownPartError(msg + ", then " + peekHex(part));
                }

                Log.e(TAG, msg);
                part.skip(); // an essential part to continue reading
            }

            // Debug
            //Log.e(TAG, "Unknown part encountered. id: %s, size: %s, position: %s", part.partId, part.size, part.data.getPosition());
            //part.skip(); // an essential part to continue reading
        }

        return part;
    }

    private void rememberPart(UMPPart part) {
        long position = part.data.getPosition();

        // Where the response is read to, past the last part's within a response (its header and payload come between): not past it is
        // another response's first part
        if (position <= partTrailPosition) {
            partTrailCount = 0;
        }

        partTrailPosition = position;
        partTrailIds[partTrailCount % PART_TRAIL_LENGTH] = part.partId;
        partTrailSizes[partTrailCount % PART_TRAIL_LENGTH] = part.size;
        partTrailCount++;
    }

    /**
     * The parts of the response being read, the last PART_TRAIL_LENGTH of them oldest first, as "id:size", e.g. "35:12 20:1500": the
     * broken part is the last. Only itself for a response whose first part it was.
     */
    /* package */ String getPartTrail() {
        StringBuilder trail = new StringBuilder();

        for (int i = Math.max(0, partTrailCount - PART_TRAIL_LENGTH); i < partTrailCount; i++) {
            if (trail.length() > 0) {
                trail.append(' ');
            }

            trail.append(partTrailIds[i % PART_TRAIL_LENGTH]).append(':').append(partTrailSizes[i % PART_TRAIL_LENGTH]);
        }

        return trail.toString();
    }

    /**
     * A part's payload that doesn't read as the protobuf its id says is a broken response (see BrokenPartError). Any other failure of
     * the read is the connection's, under the same wrapper as before.
     */
    private static IllegalStateException partError(IOException e) {
        return e instanceof InvalidProtocolBufferException ? new BrokenPartError(e.toString(), e) : new IllegalStateException(e);
    }

    /**
     * The first bytes after the part's header, in hex, e.g. "3c 21 44 4f": not read past (peeked, then put back)
     */
    private static String peekHex(UMPPart part) {
        byte[] bytes = new byte[Math.max(0, Math.min(part.size, PEEKED_BYTES))];

        try {
            if (bytes.length == 0 || !part.data.peekFully(bytes, 0, bytes.length, true)) {
                return "no bytes";
            }
        } catch (IOException e) {
            return "no bytes (" + e.getClass().getSimpleName() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "no bytes (interrupted)";
        } finally {
            part.data.resetPeekPosition();
        }

        StringBuilder result = new StringBuilder();

        for (byte value : bytes) {
            if (result.length() > 0) {
                result.append(' ');
            }

            int unsigned = value & 0xFF;
            result.append(unsigned < 0x10 ? "0" : "").append(Integer.toHexString(unsigned));
        }

        return result.toString();
    }

    public String getUrl() {
        return this.url;
    }

    public void setServerAbrStreamingUrl(String url) {
        setUrl(url);
    }

    /**
     * Whether the stream can go on at the URL: not a live stream's of another broadcast, which it would have to start again at
     */
    public boolean acceptsUrl(@Nullable String url) {
        if (!processor.isLive() || this.url == null) {
            return true;
        }

        return url != null && Helpers.equals(UrlQueryStringFactory.parse(url).get("id"), UrlQueryStringFactory.parse(this.url).get("id"));
    }

    private void setUrl(String url) {
        Log.d(TAG, "New URL: %s", url);
        UrlQueryString newQueryString = UrlQueryStringFactory.parse(url);
        if (!acceptsUrl(url)) {
            String bc = UrlQueryStringFactory.parse(this.url).get("id");
            throw new SabrStreamError(String.format("Broadcast ID changed from %s to %s. The download will need to be restarted.", bc, newQueryString.get("id")));
        }
        this.url = url;
        if (Helpers.equals(newQueryString.get("source"), "yt_live_broadcast")) {
            processor.setLive(true);
        }
    }
}
