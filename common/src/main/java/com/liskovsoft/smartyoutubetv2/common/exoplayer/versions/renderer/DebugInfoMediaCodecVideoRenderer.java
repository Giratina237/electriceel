package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import android.content.Context;
import android.media.MediaCodec;
import android.os.Build.VERSION;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Surface;
import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.TunneledFrames;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.ExoUtils;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class DebugInfoMediaCodecVideoRenderer extends MediaCodecVideoRenderer {
    /**
     * Told, on the playback thread, of the keyframe the decoder started from to reach a position (see #setKeyframeListener)
     */
    public interface KeyframeListener {
        /**
         * @param keyframeUs its time, as a seek's (the period's)
         * @param positionUs the position it's decoded to, as a seek's
         */
        void onDecodedFromKeyframe(long keyframeUs, long positionUs);
    }

    @Nullable
    private volatile KeyframeListener mKeyframeListener;
    private static final String TAG = DebugInfoMediaCodecVideoRenderer.class.getSimpleName();
    /**
     * Shows the next decoded frame while paused. Payload: the request number, see {@link #getLastFrameStepRequest()}.
     */
    public static final int MSG_FRAME_STEP = C.MSG_CUSTOM_BASE + 1;
    /**
     * Does nothing, but wakes the paused playback loop, which otherwise renders once a second.
     */
    public static final int MSG_FRAME_STEP_WAKE = C.MSG_CUSTOM_BASE + 2;
    /**
     * The tunneled decoders that reported a frame past the first (see {@link #isRenderedCountKnown()})
     */
    private static final Set<String> sFrameReportingDecoders = Collections.synchronizedSet(new HashSet<>());
    private int mFrameIndex;
    private boolean mIsSetOutputSurfaceWorkaroundEnabled;
    // Playback thread only
    private int mPendingFrameSteps;
    // Read by the main thread
    private volatile boolean mHasPendingFrameSteps;
    private volatile int mLastFrameStepRequest;
    private volatile long mFrameStepPresentationTimeUs = C.TIME_UNSET;
    private volatile int mFrameStepRequest;
    private volatile int mFrameStepCount;
    private volatile boolean mIsFrameStepSupported;
    /**
     * Of the frames decoded only to reach the position (e.g. a resume's, from the keyframe before it), since the renderer was enabled
     * or its position reset: how many, and when the first was ({@link SystemClock#elapsedRealtime()}, -1 for none). Read by the main
     * thread at the first frame (see VideoStarts#onFirstFrame).
     */
    private volatile int mSkippedToPosition;
    private volatile long mSkipStartMs = -1;
    /**
     * A frame at or past the position came out of the decoder since the renderer was enabled or its position reset: it got there (see
     * #hasReachedPosition). Read by the main thread.
     */
    private volatile boolean mHasReachedPosition;
    /**
     * Tunneled, how far past the position the decoder is given samples: the rest waits in the renderer's queue (see
     * {@link #shouldFeedInput})
     */
    private static final long TUNNELED_FEED_AHEAD_US = 2_000_000;
    // Playback thread, read by the main one
    private volatile boolean mIsTunneling;
    @Nullable
    private String mDecoderName;
    // Read by the main thread
    private volatile boolean mIsRenderedCountKnown = true;
    /**
     * In the renderer's time ({@link C#TIME_UNSET} for none): the position it was last rendered at, the last sample given to the decoder
     * since its codec was flushed, and the last frame a tunneled decoder said it showed since the renderer was enabled, with when it
     * said so ({@link SystemClock#elapsedRealtime()}, -1 for none). Set on the playback thread, read by the main one too (see
     * {@link #getTunneledFrames()}).
     */
    private volatile long mPositionUs = C.TIME_UNSET;
    private volatile long mLastQueuedUs = C.TIME_UNSET;
    private volatile long mLastShownUs = C.TIME_UNSET;
    private volatile long mLastShownAtMs = -1;

    // Exo 2.9
    //public DebugInfoMediaCodecVideoRenderer(Context context, MediaCodecSelector mediaCodecSelector, long allowedJoiningTimeMs,
    //                                     @Nullable DrmSessionManager<FrameworkMediaCrypto> drmSessionManager, boolean playClearSamplesWithoutKeys,
    //                                     @Nullable Handler eventHandler, @Nullable VideoRendererEventListener eventListener,
    //                                     int maxDroppedFramesToNotify) {
    //    super(context, mediaCodecSelector, allowedJoiningTimeMs, drmSessionManager, playClearSamplesWithoutKeys, eventHandler, eventListener,
    //            maxDroppedFramesToNotify);
    //}

    // Exo 2.10, 2.11
    public DebugInfoMediaCodecVideoRenderer(Context context, MediaCodecSelector mediaCodecSelector, long allowedJoiningTimeMs,
                                            @Nullable DrmSessionManager<FrameworkMediaCrypto> drmSessionManager, boolean playClearSamplesWithoutKeys, boolean enableDecoderFallback, @Nullable Handler eventHandler, @Nullable VideoRendererEventListener eventListener, int maxDroppedFramesToNotify) {
        super(context, mediaCodecSelector, allowedJoiningTimeMs, drmSessionManager, playClearSamplesWithoutKeys, enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify);
    }

    // Exo 2.12, 2.13
    //public DebugInfoMediaCodecVideoRenderer(Context context, MediaCodecSelector mediaCodecSelector, long allowedJoiningTimeMs,
    //                                     boolean enableDecoderFallback, @Nullable Handler eventHandler,
    //                                     @Nullable VideoRendererEventListener eventListener, int maxDroppedFramesToNotify) {
    //    super(context, mediaCodecSelector, allowedJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, maxDroppedFramesToNotify);
    //}

    @Override
    protected CodecMaxValues getCodecMaxValues(
            MediaCodecInfo codecInfo, Format format, Format[] streamFormats) {
        ExoUtils.updateVideoDecoderInfo(codecInfo);

        return super.getCodecMaxValues(codecInfo, format, streamFormats);
    }

    // Measure real fps.
    // Note, that you can't accurate measure frame rate because actual frame rate is the average frame rate for the whole video track!
    // 29.97fps test: https://www.youtube.com/watch?v=LXb3EKWsInQ (Costa Rica)
    // More info: https://github.com/google/ExoPlayer/issues/4088
    //@Override
    //protected void renderOutputBuffer(MediaCodec codec, int index, long presentationTimeUs) {
    //    super.renderOutputBuffer(codec, index, presentationTimeUs);
    //}
    //
    //@Override
    //protected void renderOutputBufferV21(MediaCodec codec, int index, long presentationTimeUs, long releaseTimeNs) {
    //    super.renderOutputBufferV21(codec, index, presentationTimeUs, releaseTimeNs);
    //
    //    mFrameIndex++;
    //
    //    Log.d(TAG, "Real fps: %s", 1_000_000f / (presentationTimeUs / mFrameIndex));
    //}

    @Override
    protected boolean codecNeedsSetOutputSurfaceWorkaround(String name) {
        // Null surface error on Android 9 (VERSION.SDK_INT >= 28) and above (appears on background audio playback)
        // Need to be enabled on older version of ExoPlayer (e.g. 2.10.6).
        // It's because there's no tweaks for modern devices.
        return mIsSetOutputSurfaceWorkaroundEnabled || super.codecNeedsSetOutputSurfaceWorkaround(name);
    }

    /**
     * Null surface error on Android 9 (VERSION.SDK_INT >= 28) and above (appears on background audio playback)<br/>
     * Need to be enabled on older version of ExoPlayer (e.g. 2.10.6).<br/>
     * It's because there's no tweaks for modern devices.
     */
    public void enableSetOutputSurfaceWorkaround(boolean enable) {
        mIsSetOutputSurfaceWorkaroundEnabled = enable;
    }

    @Override
    public void handleMessage(int messageType, @Nullable Object message) throws ExoPlaybackException {
        if (messageType == MSG_FRAME_STEP) {
            if (mIsFrameStepSupported) {
                setPendingFrameSteps(mPendingFrameSteps + 1);
            }
            mLastFrameStepRequest = (Integer) message;
        } else if (messageType != MSG_FRAME_STEP_WAKE) {
            super.handleMessage(messageType, message);
        }
    }

    @Override
    protected void onEnabled(boolean joining) throws ExoPlaybackException {
        super.onEnabled(joining);

        mIsTunneling = getConfiguration().tunnelingAudioSessionId != C.AUDIO_SESSION_ID_UNSET;
        // Tunneled frames go straight from the decoder to the display, so there's no decoded frame to hold back and release
        mIsFrameStepSupported = !mIsTunneling;
        mIsRenderedCountKnown = !mIsTunneling;
        resetSkippedToPosition();
        mPositionUs = C.TIME_UNSET;
        mLastQueuedUs = C.TIME_UNSET;
        mLastShownUs = C.TIME_UNSET;
        mLastShownAtMs = -1;
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        mPositionUs = positionUs;
        super.render(positionUs, elapsedRealtimeUs);
    }

    /**
     * Tunneled, the decoder shows what it's given itself, when the clock gets there, and was given whatever it had room for: one that
     * frees its input as soon as it takes it would get all that's loaded of a stream of few small frames. The renderer then had no
     * sample left in its queue and wasn't ready, while the buffer counted as full and nothing more loaded: the player waited for good
     * (96YV3AK4: 6 fps at 160 kbps, 30-35 s buffered and nothing loaded for 20 s, each reload played 2 s more and stalled again). Given
     * no more than that past the position, the rest stays in the queue: the renderer is ready as an untunneled one is, and loading goes
     * on as the position nears what's buffered. At usual frame rates the decoder's own input buffers run out well before.
     */
    @Override
    protected boolean shouldFeedInput(long positionUs) {
        long queuedUs = mLastQueuedUs;

        return !mIsTunneling || queuedUs == C.TIME_UNSET || queuedUs - positionUs < TUNNELED_FEED_AHEAD_US;
    }

    @Override
    protected void onQueueInputBuffer(DecoderInputBuffer buffer) {
        super.onQueueInputBuffer(buffer);

        if (mIsTunneling) {
            long queuedUs = mLastQueuedUs;
            mLastQueuedUs = queuedUs == C.TIME_UNSET ? buffer.timeUs : Math.max(queuedUs, buffer.timeUs);
        }
    }

    @Override
    protected void releaseCodec() {
        try {
            super.releaseCodec();
        } finally {
            // What it was given went with it
            mLastQueuedUs = C.TIME_UNSET;
        }
    }

    @Override
    protected boolean flushOrReleaseCodec() {
        try {
            return super.flushOrReleaseCodec();
        } finally {
            mLastQueuedUs = C.TIME_UNSET;
        }
    }

    @Override
    protected void onCodecInitialized(String name, long initializedTimestampMs, long initializationDurationMs) {
        super.onCodecInitialized(name, initializedTimestampMs, initializationDurationMs);

        mDecoderName = name;
        mIsRenderedCountKnown = !mIsTunneling || sFrameReportingDecoders.contains(name);
    }

    @Override
    protected void onProcessedTunneledBuffer(long presentationTimeUs) {
        super.onProcessedTunneledBuffer(presentationTimeUs);
        mLastShownUs = presentationTimeUs;
        mLastShownAtMs = SystemClock.elapsedRealtime();

        // Counted once the decoder has reported a frame past the first: it reports the frames it shows
        if (!mIsRenderedCountKnown && decoderCounters.renderedOutputBufferCount > 1) {
            if (mDecoderName != null) {
                sFrameReportingDecoders.add(mDecoderName);
            }

            mIsRenderedCountKnown = true;
        }
    }

    @Override
    protected void onStarted() {
        super.onStarted();
        setPendingFrameSteps(0); // a step is only for the frame on screen right now
    }

    @Override
    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
        super.onPositionReset(positionUs, joining);
        setPendingFrameSteps(0);
        resetSkippedToPosition();
        // The codec was flushed: what it was given is gone
        mLastQueuedUs = C.TIME_UNSET;
    }

    @Override
    protected void onDisabled() {
        super.onDisabled();
        setPendingFrameSteps(0);
        mPositionUs = C.TIME_UNSET;
        mLastQueuedUs = C.TIME_UNSET;
    }

    @Override
    protected boolean processOutputBuffer(long positionUs, long elapsedRealtimeUs, MediaCodec codec, ByteBuffer buffer, int bufferIndex,
                                          int bufferFlags, long bufferPresentationTimeUs, boolean isDecodeOnlyBuffer, boolean isLastBuffer,
                                          Format format) throws ExoPlaybackException {
        // Skipped by the renderer at once: decoded only to get to the position
        if (isDecodeOnlyBuffer && !isLastBuffer) {
            if (mSkipStartMs < 0) {
                mSkipStartMs = SystemClock.elapsedRealtime();
                // The first frame decoded since the position was set: the keyframe before it
                KeyframeListener listener = mKeyframeListener;

                if (listener != null) {
                    long offsetUs = getOutputStreamOffsetUs();
                    listener.onDecodedFromKeyframe(bufferPresentationTimeUs - offsetUs, positionUs - offsetUs);
                }
            }

            mSkippedToPosition++;
        } else if (!isDecodeOnlyBuffer) {
            mHasReachedPosition = true;
        }

        if (super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer, bufferIndex, bufferFlags, bufferPresentationTimeUs,
                isDecodeOnlyBuffer, isLastBuffer, format)) {
            return true;
        }

        if (mPendingFrameSteps == 0 || isDecodeOnlyBuffer || getState() == STATE_STARTED) {
            return false;
        }

        // Paused, so this decoded frame would wait for playback to reach it. Show it now: a step that needs no seek.
        long presentationTimeUs = bufferPresentationTimeUs - getOutputStreamOffsetUs();
        renderOutputBufferV21(codec, bufferIndex, presentationTimeUs, System.nanoTime());
        mFrameStepPresentationTimeUs = presentationTimeUs;
        mFrameStepRequest = mLastFrameStepRequest;
        mFrameStepCount++;
        setPendingFrameSteps(mPendingFrameSteps - 1);

        return true;
    }

    /**
     * Whether the frames rendered are counted (see DecoderCounters#renderedOutputBufferCount). Tunneled frames are counted as the
     * decoder reports them shown (see MediaCodec.OnFrameRenderedListener), which Android doesn't promise for each frame: a decoder
     * that reported only the first leaves the count standing still while the picture moves. Not known till the decoder in use has
     * reported a frame past the first, in this run of the app.
     */
    public boolean isRenderedCountKnown() {
        return mIsRenderedCountKnown;
    }

    /**
     * The decoder shows the frames itself, in step with the audio (see Configuration#tunnelingAudioSessionId)
     */
    public boolean isTunneling() {
        return mIsTunneling;
    }

    /**
     * Where a tunneled decoder is at against the position, null when it isn't tunneled: the frames it shows aren't the renderer's to
     * see, only what it says it showed (see MediaCodec.OnFrameRenderedListener). A picture that stands still while the position moves
     * is told apart by it: the frames given ahead and not shown (late, dropped by the decoder), or none given.
     */
    @Nullable
    public TunneledFrames getTunneledFrames() {
        if (!mIsTunneling) {
            return null;
        }

        long positionUs = mPositionUs;
        long shownUs = mLastShownUs;
        long shownAtMs = mLastShownAtMs;
        long queuedUs = mLastQueuedUs;
        boolean hasPosition = positionUs != C.TIME_UNSET;

        return new TunneledFrames(
                hasPosition && shownUs != C.TIME_UNSET ? (positionUs - shownUs) / 1_000 : null,
                shownAtMs >= 0 ? SystemClock.elapsedRealtime() - shownAtMs : null,
                hasPosition && queuedUs != C.TIME_UNSET ? (queuedUs - positionUs) / 1_000 : null);
    }

    /**
     * E.g. a resume goes back to the keyframe (see ExoPlayerController#setResumePositionMs). Not told in tunneling, where the decoder
     * shows the frames itself.
     */
    public void setKeyframeListener(@Nullable KeyframeListener listener) {
        mKeyframeListener = listener;
    }

    /**
     * The frames decoded only to reach the position since it was set (e.g. a resume's, from the keyframe before it)
     */
    public int getSkippedToPosition() {
        return mSkippedToPosition;
    }

    /**
     * When the first of them was decoded ({@link SystemClock#elapsedRealtime()}), -1 for none
     */
    public long getSkipStartMs() {
        return mSkipStartMs;
    }

    /**
     * The decoder got to the position it was sent to (e.g. a resume's): a frame there came out of it, and is shown as the playback
     * reaches it, at once for the first one. Going back to the keyframe before it then (see ExoPlayerController#setResumePositionMs)
     * saves nothing.
     */
    public boolean hasReachedPosition() {
        return mHasReachedPosition;
    }

    private void resetSkippedToPosition() {
        mSkipStartMs = -1;
        mSkippedToPosition = 0;
        mHasReachedPosition = false;
    }

    public boolean hasPendingFrameSteps() {
        return mHasPendingFrameSteps;
    }

    public int getLastFrameStepRequest() {
        return mLastFrameStepRequest;
    }

    /**
     * Presentation time (period time) of the frame the last step showed.
     */
    public long getFrameStepPresentationTimeUs() {
        return mFrameStepPresentationTimeUs;
    }

    /**
     * The last request received before the frame the last step showed.
     */
    public int getFrameStepRequest() {
        return mFrameStepRequest;
    }

    /**
     * Number of frames shown by steps so far. Bumped after the other frame step values are updated.
     */
    public int getFrameStepCount() {
        return mFrameStepCount;
    }

    private void setPendingFrameSteps(int count) {
        mPendingFrameSteps = count;
        mHasPendingFrameSteps = count > 0;
    }
}
