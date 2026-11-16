package com.google.android.exoplayer2.source.sabr.manifest;

import android.os.SystemClock;
import android.util.Base64;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.offline.FilterableManifest;
import com.google.android.exoplayer2.offline.StreamKey;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.misc.EnabledTrackTypes;
import com.google.android.exoplayer2.source.sabr.parser.misc.Utils;
import com.google.android.exoplayer2.source.sabr.parser.models.FormatSelector;
import com.google.android.exoplayer2.source.sabr.parser.models.SabrFormatMetadata;
import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.BufferedRange;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.ClientAbrState;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.MediaHeader;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext.ClientInfo;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.TimeRange;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.google.protobuf.ByteString;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a SABR media presentation
 */
public class SabrManifest implements FilterableManifest<SabrManifest> {
    private static final String TAG = SabrManifest.class.getSimpleName();

    /**
     * The {@code availabilityStartTime} value in milliseconds since epoch, or {@link C#TIME_UNSET} if
     * not present.
     */
    public final long availabilityStartTimeMs;

    /**
     * The duration of the presentation in milliseconds, or {@link C#TIME_UNSET} if not applicable.
     */
    public final long durationMs;

    /**
     * The {@code minBufferTime} value in milliseconds, or {@link C#TIME_UNSET} if not present.
     */
    public final long minBufferTimeMs;

    /**
     * The {@code timeShiftBufferDepth} value in milliseconds, or {@link C#TIME_UNSET} if not
     * present.
     */
    public final long timeShiftBufferDepthMs;

    /**
     * The {@code suggestedPresentationDelay} value in milliseconds, or {@link C#TIME_UNSET} if not
     * present.
     */
    public final long suggestedPresentationDelayMs;

    /**
     * The {@code publishTime} value in milliseconds since epoch, or {@link C#TIME_UNSET} if
     * not present.
     */
    public final long publishTimeMs;

    public final List<Period> periods;

    /**
     * Whether the manifest has value "dynamic" for the {@code type} attribute.
     */
    public final boolean dynamic;

    /**
     * The {@code minimumUpdatePeriod} value in milliseconds, or {@link C#TIME_UNSET} if not
     * applicable.
     */
    public final long minUpdatePeriodMs;

    @Nullable public final String visitorCookie;

    /**
     * Of {@link #liveSegmentTargetDurationSec}: the stream isn't live
     */
    public static final int NOT_LIVE = -1;

    /**
     * A live stream's segment length (see SabrManifestParser), {@link #NOT_LIVE} for a video or a finished stream
     */
    public final int liveSegmentTargetDurationSec;

    /**
     * A new session asked for (see requestSessionRefresh) and not given in this long: it failed
     */
    @VisibleForTesting
    static final long SESSION_REFRESH_TIMEOUT_MS = 30_000;
    /**
     * Asked for again this soon after a new session was swapped in: it didn't help
     */
    @VisibleForTesting
    static final long MIN_SESSION_LIFETIME_MS = 60_000;

    private final String videoId;
    /**
     * The URL, the ustreamer config and the poToken are the session's: swapped for a new one's (see requestSessionRefresh)
     */
    private SabrCdnSelector cdnSelector;
    private volatile String videoPlaybackUstreamerConfig;
    private volatile String poToken;
    private final ClientInfo clientInfo;
    private final Map<Integer, SabrStream> sabrStreams;
    private int sabrRequestNumber = -1;
    private final FormatSelector emptySelector;
    @Nullable private SessionRefresher sessionRefresher;
    /**
     * Of the session in use: the next one's is one more (see getSessionId)
     */
    private int sessionId;
    /**
     * When the session in use was swapped in (SystemClock#elapsedRealtime), -1 for the first one
     */
    private long sessionStartMs = -1;
    /**
     * When the new session being asked for was (SystemClock#elapsedRealtime), -1 for none
     */
    private long sessionRefreshStartMs = -1;
    /**
     * A new session was asked for and not had, or didn't help: the next ask for one fails the stream, which the app reloads with the
     * video
     */
    private boolean isSessionRefreshFailed;

    /**
     * Asks for the player response again when the server asks for it (see SabrReloadPlayerError), e.g. as the session expires about
     * 6 h into a video
     */
    public interface SessionRefresher {
        /**
         * Called on the playback thread, once per ask: the player response is fetched on another thread, and its session given to
         * the callback.
         *
         * @param reloadPlaybackToken the server's, for the player request (see ReloadPlayerResponse), null if it gave none
         */
        void refresh(@Nullable String reloadPlaybackToken, SessionCallback callback);
    }

    public interface SessionCallback {
        /**
         * On any thread
         *
         * @param session null when there's none (e.g. the fetch failed)
         * @return whether it was swapped in: not one of another client, without the stream's formats, or late
         */
        boolean onSession(@Nullable SabrSession session);
    }

    public SabrManifest(
            long availabilityStartTimeMs,
            long durationMs,
            long minBufferTimeMs,
            boolean dynamic,
            long minUpdatePeriodMs,
            long timeShiftBufferDepthMs,
            long suggestedPresentationDelayMs,
            long publishTimeMs,
            List<Period> periods,
            String serverAbrStreamingUrl,
            String videoPlaybackUstreamerConfig,
            String poToken,
            String videoId,
            ClientInfo clientInfo) {
        this(
                availabilityStartTimeMs, durationMs, minBufferTimeMs, dynamic, minUpdatePeriodMs,
                timeShiftBufferDepthMs, suggestedPresentationDelayMs, publishTimeMs, periods,
                serverAbrStreamingUrl, videoPlaybackUstreamerConfig, poToken, videoId, clientInfo,
                null);
    }

    public SabrManifest(
            long availabilityStartTimeMs,
            long durationMs,
            long minBufferTimeMs,
            boolean dynamic,
            long minUpdatePeriodMs,
            long timeShiftBufferDepthMs,
            long suggestedPresentationDelayMs,
            long publishTimeMs,
            List<Period> periods,
            String serverAbrStreamingUrl,
            String videoPlaybackUstreamerConfig,
            String poToken,
            String videoId,
            ClientInfo clientInfo,
            @Nullable String visitorCookie) {
        this(
                availabilityStartTimeMs, durationMs, minBufferTimeMs, dynamic, minUpdatePeriodMs,
                timeShiftBufferDepthMs, suggestedPresentationDelayMs, publishTimeMs, periods,
                serverAbrStreamingUrl, videoPlaybackUstreamerConfig, poToken, videoId, clientInfo,
                visitorCookie, NOT_LIVE);
    }

    public SabrManifest(
            long availabilityStartTimeMs,
            long durationMs,
            long minBufferTimeMs,
            boolean dynamic,
            long minUpdatePeriodMs,
            long timeShiftBufferDepthMs,
            long suggestedPresentationDelayMs,
            long publishTimeMs,
            List<Period> periods,
            String serverAbrStreamingUrl,
            String videoPlaybackUstreamerConfig,
            String poToken,
            String videoId,
            ClientInfo clientInfo,
            @Nullable String visitorCookie,
            int liveSegmentTargetDurationSec) {
        this.liveSegmentTargetDurationSec = liveSegmentTargetDurationSec;
        this.availabilityStartTimeMs = availabilityStartTimeMs;
        this.durationMs = durationMs;
        this.minBufferTimeMs = minBufferTimeMs;
        this.dynamic = dynamic;
        this.minUpdatePeriodMs = minUpdatePeriodMs;
        this.timeShiftBufferDepthMs = timeShiftBufferDepthMs;
        this.suggestedPresentationDelayMs = suggestedPresentationDelayMs;
        this.publishTimeMs = publishTimeMs;
        this.periods = periods;
        this.videoId = videoId;
        this.cdnSelector = new SabrCdnSelector(serverAbrStreamingUrl);
        this.videoPlaybackUstreamerConfig = videoPlaybackUstreamerConfig;
        this.clientInfo = clientInfo;
        this.poToken = poToken;
        this.visitorCookie = visitorCookie;
        this.sabrStreams = new HashMap<>();
        this.emptySelector = new FormatSelector("ignored", true);
    }

    public final int getPeriodCount() {
        return periods.size();
    }

    public final Period getPeriod(int index) {
        return periods.get(index);
    }

    public final long getPeriodDurationMs(int index) {
        return index == periods.size() - 1
                ? (durationMs == C.TIME_UNSET ? C.TIME_UNSET : (durationMs - periods.get(index).startMs))
                : (periods.get(index + 1).startMs - periods.get(index).startMs);
    }

    public final long getPeriodDurationUs(int index) {
        return C.msToUs(getPeriodDurationMs(index));
    }

    @Override
    public SabrManifest copy(List<StreamKey> streamKeys) {
        return null;
    }

    /**
     * A live stream's, not a finished one's (see {@link #liveSegmentTargetDurationSec})
     */
    public final boolean isLive() {
        return liveSegmentTargetDurationSec != NOT_LIVE;
    }

    public final String getVideoId() {
        return videoId;
    }

    public synchronized SabrStream getSabrStream(int trackType) {
        SabrStream sabrStream = sabrStreams.get(trackType);

        if (sabrStream != null) {
            return sabrStream;
        }

        boolean isLive = isLive();
        sabrStream = new SabrStream(
                cdnSelector.getCurrentUrl(),
                videoPlaybackUstreamerConfig,
                clientInfo,
                isLive ? liveSegmentTargetDurationSec : -1,
                -1,
                -1,
                poToken,
                false,
                videoId,
                durationMs
        );
        // The server's media headers of a live stream have no duration: estimated from the target (see SabrProcessor)
        sabrStream.setLive(isLive);

        sabrStreams.put(trackType, sabrStream);

        return sabrStream;
    }

    /**
     * Moves all active SABR streams to the next media network advertised by the
     * signed URL. A failure from an earlier in-flight request is also retryable
     * after another track has already advanced the selector.
     */
    public synchronized boolean maybeUseNextCdn(String failedUrl) {
        if (!cdnSelector.maybeAdvance(failedUrl)) {
            return false;
        }

        String nextUrl = cdnSelector.getCurrentUrl();

        for (SabrStream sabrStream : sabrStreams.values()) {
            sabrStream.setServerAbrStreamingUrl(nextUrl);
        }

        return true;
    }

    public synchronized void setSessionRefresher(@Nullable SessionRefresher refresher) {
        sessionRefresher = refresher;
    }

    /**
     * Of the session the requests are made with now: a request answered with an ask for a new one tells which it was made with (see
     * requestSessionRefresh)
     */
    public synchronized int getSessionId() {
        return sessionId;
    }

    /**
     * The server asked for the player response again (see ReloadPlayerResponse): it sends no media with this session. A new one is
     * asked for (see SessionRefresher), and swapped in while the player plays what it buffered. Before, the player was failed and
     * the app reloaded the video with it: the playback stopped for about 3 s, and the audio output was closed and opened again
     * (SmartTube #6478, about 6 h into a long video).
     *
     * @param requestSessionId of the session the request was made with (see getSessionId)
     * @return whether the requests wait for the new session, or are made with one already in: false when there's no new one to
     * have here (no refresher, or one failed or didn't help), and the stream fails as before
     */
    public boolean requestSessionRefresh(int requestSessionId, @Nullable String reloadPlaybackToken) {
        SessionRefresher refresher;
        int refreshedSessionId;

        synchronized (this) {
            if (requestSessionId != sessionId) {
                // Made with the session before: the next request has the new one
                return true;
            }

            if (isSessionRefreshPending()) {
                return true; // the other track asked for it
            }

            if (sessionRefresher == null || isSessionRefreshFailed) {
                return false;
            }

            long nowMs = SystemClock.elapsedRealtime();

            if (sessionStartMs != -1 && nowMs - sessionStartMs < MIN_SESSION_LIFETIME_MS) {
                Log.e(TAG, "Asked for a new session %s ms after the last one: it didn't help", nowMs - sessionStartMs);
                isSessionRefreshFailed = true;
                return false;
            }

            Log.d(TAG, "Asking for a new session of %s", videoId);
            sessionRefreshStartMs = nowMs;
            refresher = sessionRefresher;
            refreshedSessionId = sessionId;
        }

        // Not holding the lock: the callback may come at once
        try {
            refresher.refresh(reloadPlaybackToken, session -> onSessionRefreshed(refreshedSessionId, session));
        } catch (RuntimeException e) {
            Log.e(TAG, "Session refresh failed to start: %s", e.getMessage());
            onSessionRefreshed(refreshedSessionId, null);
        }

        synchronized (this) {
            return !isSessionRefreshFailed;
        }
    }

    /**
     * A new session is asked for and not given yet (see requestSessionRefresh): the requests wait for it. One not given in
     * SESSION_REFRESH_TIMEOUT_MS failed.
     */
    public synchronized boolean isSessionRefreshPending() {
        if (sessionRefreshStartMs == -1) {
            return false;
        }

        if (SystemClock.elapsedRealtime() - sessionRefreshStartMs >= SESSION_REFRESH_TIMEOUT_MS) {
            Log.e(TAG, "No new session in %s ms", SESSION_REFRESH_TIMEOUT_MS);
            sessionRefreshStartMs = -1;
            isSessionRefreshFailed = true;
            return false;
        }

        return true;
    }

    /**
     * @param refreshedSessionId of the session in use when the new one was asked for
     * @return whether the session was swapped in
     */
    private synchronized boolean onSessionRefreshed(int refreshedSessionId, @Nullable SabrSession session) {
        if (refreshedSessionId != sessionId || sessionRefreshStartMs == -1) {
            Log.w(TAG, "A new session came after the ask for it timed out");
            return false;
        }

        sessionRefreshStartMs = -1;
        String rejection = session != null ? getRejection(session) : "none came";

        if (rejection != null) {
            Log.e(TAG, "No new session: %s", rejection);
            isSessionRefreshFailed = true;
            return false;
        }

        cdnSelector = new SabrCdnSelector(session.serverAbrStreamingUrl);
        videoPlaybackUstreamerConfig = session.videoPlaybackUstreamerConfig;
        poToken = session.poToken;
        String url = cdnSelector.getCurrentUrl();

        // The streams keep what they have of the playback (e.g. its cookie, the formats initialized): it goes on where it was
        for (SabrStream sabrStream : sabrStreams.values()) {
            sabrStream.setServerAbrStreamingUrl(url);
            sabrStream.setPoToken(session.poToken);
        }

        sessionId++;
        sessionStartMs = SystemClock.elapsedRealtime();
        Log.d(TAG, "New session swapped in: %s", url);

        return true;
    }

    /**
     * @return why the streams can't go on with the session, null when they can
     */
    @Nullable
    private String getRejection(SabrSession session) {
        if (session.clientInfo.getClientName() != clientInfo.getClientName()) {
            return String.format("of the client %s, not %s", session.clientInfo.getClientName(), clientInfo.getClientName());
        }

        for (SabrStream sabrStream : sabrStreams.values()) {
            if (!sabrStream.acceptsUrl(session.serverAbrStreamingUrl)) {
                return "of another broadcast";
            }

            for (FormatId formatId : sabrStream.getFormatSelector().formatIds) {
                if (!session.itags.contains(formatId.getItag())) {
                    return String.format("without the format %s", formatId.getItag());
                }
            }
        }

        return null;
    }

    public int getSabrRequestNumber() {
        return sabrRequestNumber;
    }

    public synchronized String getRequestUrl(int trackType) {
        SabrStream activeStream = sabrStreams.get(trackType);

        if (activeStream == null) {
            throw new IllegalStateException("Active SabrStream not found for track type " + trackType);
        }

        return Utils.updateQuery(activeStream.getUrl(), "rn", ++sabrRequestNumber);
    }

    public VideoPlaybackAbrRequest createVideoPlaybackAbrRequest(int trackType, boolean isInit) {
        return createVideoPlaybackAbrRequest(trackType, isInit, C.TIME_UNSET);
    }

    public VideoPlaybackAbrRequest createVideoPlaybackAbrRequest(int trackType, boolean isInit, long seekTimeUs) {
        return createVideoPlaybackAbrRequest(trackType, isInit, seekTimeUs, null);
    }

    /**
     * A live stream's request for the segment after the given one, as if it were the newest received: the player asks for one segment
     * of a track at a time, and an edge answers each in about a second however old it is (see DefaultSabrChunkSource's prefetch)
     *
     * @param lastSegment the format's segment taken as the newest one buffered: its sequence number, start and duration
     */
    public VideoPlaybackAbrRequest createLiveRequestAfter(int trackType, MediaHeader lastSegment) {
        return createVideoPlaybackAbrRequest(trackType, false,
                (lastSegment.getStartMs() + lastSegment.getDurationMs()) * 1_000L, lastSegment);
    }

    private VideoPlaybackAbrRequest createVideoPlaybackAbrRequest(int trackType, boolean isInit, long seekTimeUs,
                                                                  @Nullable MediaHeader lastSegment) {
        SabrStream activeStream = sabrStreams.get(trackType);

        if (activeStream == null) {
            throw new IllegalStateException("Active SabrStream not found for track type " + trackType);
        }

        Format selectedVideoFormat = getFormatSelector(C.TRACK_TYPE_VIDEO).getSelectedFormat();
        Format selectedAudioFormat = getFormatSelector(C.TRACK_TYPE_AUDIO).getSelectedFormat();
        int height = trackType == C.TRACK_TYPE_VIDEO && selectedVideoFormat != null
                ? selectedVideoFormat.height : -1;
        int bandwidthEstimate = trackType == C.TRACK_TYPE_VIDEO && selectedVideoFormat != null
                ? selectedVideoFormat.bitrate : selectedAudioFormat != null ? selectedAudioFormat.bitrate : -1;

        FormatId formatId = getFormatSelector(trackType).getSelectedFormatId();
        long startTimeMs = isInit ? 0 : seekTimeUs != C.TIME_UNSET
                ? seekTimeUs / 1_000 : activeStream.getSegmentStartTimeMs(formatId != null ? formatId.getItag() : -1);

        ClientAbrState.Builder clientAbrStateBuilder = ClientAbrState.newBuilder()
                .setSabrForceMaxNetworkInterruptionDurationMs(0)
                .setPlaybackRate(1)
                .setPlayerTimeMs(startTimeMs)
                .setClientViewportIsFlexible(false)
                .setBandwidthEstimate(bandwidthEstimate)
                .setDrcEnabled(false)
                .setEnabledTrackTypesBitfield(height != -1 ? EnabledTrackTypes.VIDEO_ONLY : EnabledTrackTypes.AUDIO_ONLY);

        if (height != -1) {
            clientAbrStateBuilder
                    .setStickyResolution(height)
                    .setLastManualSelectedResolution(height);
        }

        // Voice boost is opt-in. Tell the server when the user has picked it.
        if (selectedAudioFormat != null && selectedAudioFormat.isVb) {
            clientAbrStateBuilder.setEnableVoiceBoost(true);
        }

        if (trackType == C.TRACK_TYPE_AUDIO
                && selectedAudioFormat != null
                && selectedAudioFormat.metadata != null) {
            for (int i = 0; i < selectedAudioFormat.metadata.length(); i++) {
                if (selectedAudioFormat.metadata.get(i) instanceof SabrFormatMetadata) {
                    String audioTrackId = ((SabrFormatMetadata)
                            selectedAudioFormat.metadata.get(i)).audioTrackId;

                    if (audioTrackId != null && !audioTrackId.isEmpty()) {
                        clientAbrStateBuilder.setAudioTrackId(audioTrackId);
                    }
                }
            }
        }

        ClientAbrState clientAbrState = clientAbrStateBuilder.build();

        Pair<List<BufferedRange>, FormatId> bufferRanges = addBufferingInfoToAbrRequest(trackType, lastSegment);

        List<FormatId> selectedFormats = createSelectedFormatIds(trackType);

        if (isInit) {
            selectedFormats.clear();
        }

        if (bufferRanges.second != null) {
            selectedFormats.add(0, bufferRanges.second);
        }

        return VideoPlaybackAbrRequest.newBuilder()
                .setClientAbrState(clientAbrState)
                .addAllPreferredVideoFormatIds(getFormatSelector(C.TRACK_TYPE_VIDEO).formatIds)
                .addAllPreferredAudioFormatIds(getFormatSelector(C.TRACK_TYPE_AUDIO).formatIds)
                .addAllPreferredSubtitleFormatIds(getFormatSelector(C.TRACK_TYPE_TEXT).formatIds)
                .addAllSelectedFormatIds(selectedFormats)
                .addAllBufferedRanges(bufferRanges.first)
                .setVideoPlaybackUstreamerConfig(
                        ByteString.copyFrom(
                                Base64.decode(videoPlaybackUstreamerConfig, Base64.URL_SAFE)
                        )
                )
                .setStreamerContext(createStreamerContext(trackType))
                .build();
    }

    /**
     * Adds buffering information to the ABR request for all active formats.<br/><br/>
     *
     * NOTE:
     * On the web, mobile, and TV clients, buffered ranges in combination to player time is what dictates the segments you get.
     * In our case, we are cheating a bit by abusing the player time field (in clientAbrState), setting it to the exact start
     * time value of the segment we want, while YouTube simply uses the actual player time.<br/><br/>
     *
     * We don't have to fully replicate this behavior for two reasons:
     * 1. The SABR server will only send so much segments for a given player time. That means players like Shaka would
     * not be able to buffer more than what the server thinks is enough. It would behave like YouTube's.
     * 2. We don't have to know what segment a buffered range starts/ends at. It is easy to do in Shaka, but not in other players.
     *
     * @return The format to discard (if any) - typically formats that are active but not currently requested.
     */
    private Pair<List<BufferedRange>, FormatId> addBufferingInfoToAbrRequest(int trackType, @Nullable MediaHeader lastSegment) {
        SabrStream activeStream = sabrStreams.get(trackType);

        if (activeStream == null) {
            throw new IllegalStateException("Active SabrStream not found for track type " + trackType);
        }

        FormatId audioFormat = getFormatSelector(C.TRACK_TYPE_AUDIO).getSelectedFormatId();
        FormatId videoFormat = getFormatSelector(C.TRACK_TYPE_VIDEO).getSelectedFormatId();

        FormatId formatToDiscard = null;
        List<BufferedRange> bufferedRanges = new ArrayList<>();

        FormatId currentFormat = trackType == C.TRACK_TYPE_VIDEO ? videoFormat : audioFormat;
        int currentFormatKey = currentFormat != null ? currentFormat.getItag() : -1;

        for (FormatId activeFormat : new FormatId[]{videoFormat, audioFormat}) {
            if (activeFormat == null) {
                continue;
            }

            int activeFormatKey = activeFormat.getItag();
            boolean shouldDiscard = currentFormatKey != activeFormatKey;
            MediaHeader initializedFormat = !shouldDiscard && lastSegment != null ? lastSegment : getInitializedFormat(activeFormatKey);

            BufferedRange bufferedRange = shouldDiscard ? createFullBufferRange(activeFormat) : createPartialBufferRange(initializedFormat);

            if (bufferedRange != null) {
                bufferedRanges.add(bufferedRange);

                if (shouldDiscard) {
                    formatToDiscard = activeFormat;
                }
            }
        }

        return new Pair<>(bufferedRanges, formatToDiscard);
    }

    private @Nullable MediaHeader getInitializedFormat(int iTag) {
        MediaHeader initializedFormat = null;

        for (SabrStream sabrStream : sabrStreams.values()) {
            MediaHeader mediaHeader = sabrStream.getInitializedFormat(iTag);

            if (mediaHeader != null) {
                initializedFormat = mediaHeader;
                break;
            }
        }

        return initializedFormat;
    }

    private @NonNull FormatSelector getFormatSelector(int trackType) {
        SabrStream sabrStream = sabrStreams.get(trackType);

        if (sabrStream == null) {
            return emptySelector;
        }

        return sabrStream.getFormatSelector();
    }

    /**
     * Creates a bogus buffered range for a format. Used when we want to signal to the server to not send any
     * segments for this format.
     * @param format - The format to create a full buffer range for.
     * @return A BufferedRange object indicating the entire format is buffered.
     */
    private BufferedRange createFullBufferRange(@NonNull FormatId format) {
        // FIX: of a live stream, every segment from the first. [MAX, MAX] covered none: its server goes by the segment numbers and
        // sent the other track's segment to each request, which threw it away and never got its own (see yt-dlp's consumed range of a
        // discarded format: sequence numbers 0 to max). A video's keeps [MAX, MAX]: with [0, MAX] its server sent the other
        // track's media headers with no format metadata before them, and the init chunk failed (Initialized format not found).
        return BufferedRange.newBuilder()
                .setFormatId(format)
                .setDurationMs(Integer.MAX_VALUE)
                .setStartTimeMs(0)
                .setStartSegmentIndex(isLive() ? 0 : Integer.MAX_VALUE)
                .setEndSegmentIndex(Integer.MAX_VALUE)
                .setTimeRange(TimeRange.newBuilder()
                        .setDurationTicks(Integer.MAX_VALUE)
                        .setStartTicks(0)
                        .setTimescale(1_000)
                        .build())
                .build();
    }

    /**
     * Creates a buffered range representing a partially buffered format.
     * @param initializedFormat - The format with initialization data.
     * @return A BufferedRange object with segment information, or null if no metadata is available.
     */
    private BufferedRange createPartialBufferRange(MediaHeader initializedFormat) {
        if (initializedFormat == null) {
            return null;
        }

        int sequenceNumber = initializedFormat.hasSequenceNumber() ? initializedFormat.getSequenceNumber() : 1;

        // A live stream's server goes by the times: the segment's own, in ms. With a start at 0 and the duration's ms as ticks of the
        // media's timescale (44100 for audio), the time asked for wasn't buffered, and each request was answered with a seek back to
        // the segment's start and no media (see yt-dlp's buffered ranges)
        if (isLive() && initializedFormat.hasStartMs() && initializedFormat.getStartMs() >= 0) {
            long segmentStartMs = initializedFormat.getStartMs();
            long segmentDurationMs = initializedFormat.hasDurationMs() ? initializedFormat.getDurationMs() : 0;
            return BufferedRange.newBuilder()
                    .setFormatId(initializedFormat.getFormatId())
                    .setStartSegmentIndex(sequenceNumber)
                    .setEndSegmentIndex(sequenceNumber)
                    .setStartTimeMs(segmentStartMs)
                    .setDurationMs(segmentDurationMs)
                    .setTimeRange(TimeRange.newBuilder()
                            .setTimescale(1_000)
                            .setStartTicks(segmentStartMs)
                            .setDurationTicks(segmentDurationMs)
                            .build())
                    .build();
        }

        TimeRange timeRange = initializedFormat.hasTimeRange() ? initializedFormat.getTimeRange() : null;
        int timeScale = timeRange != null && timeRange.hasTimescale() ? timeRange.getTimescale() : 1_000;
        long startMs = initializedFormat.hasStartMs() ? initializedFormat.getStartMs() : 0;
        long durationMs = initializedFormat.hasDurationMs() ? initializedFormat.getDurationMs() : 0;
        return BufferedRange.newBuilder()
                .setFormatId(initializedFormat.getFormatId())
                .setStartSegmentIndex(sequenceNumber) // should be the real start position
                .setEndSegmentIndex(sequenceNumber) // should be the real start position
                .setStartTimeMs(0) // not used
                .setDurationMs(durationMs)
                .setTimeRange(TimeRange.newBuilder()
                        .setTimescale(timeScale)
                        .setStartTicks(0) // not used
                        .setDurationTicks(durationMs)
                        .build())
                .build();
    }

    private List<FormatId> createSelectedFormatIds(int trackType) {
        FormatSelector formatSelector = getFormatSelector(trackType);

        return new ArrayList<>(formatSelector.formatIds);
    }

    private StreamerContext createStreamerContext(int trackType) {
        SabrStream activeStream = sabrStreams.get(trackType);

        if (activeStream == null) {
            throw new IllegalStateException("Active SabrStream not found for track type " + trackType);
        }

        return activeStream.createStreamerContext();
    }
}
