package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SimpleSubtitleDecoder;
import com.google.android.exoplayer2.text.Subtitle;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.text.SubtitleTransformer;
import com.google.android.exoplayer2.text.TextOutput;
import com.google.android.exoplayer2.text.TextRenderer;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.prefs.SentenceSubtitlesData;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;

import java.util.Arrays;
import java.util.List;

/**
 * Subtitles one sentence at a time, if enabled (see SentenceSubtitlesData, Sentences): the decoders of the player's text renderer
 * and the switch between the sentences and the subtitles as they come.<br/>
 * The sentences are made of a whole subtitle file at once: a video's subtitles come as one, a live stream's in segments, which
 * show as they come.<br/>
 * The setting is switched on the playback thread, between two of the renderer's looks at the subtitle (see
 * TextRenderer#MSG_REFRESH), so its change shows at once.
 */
public final class SentenceSubtitles implements SubtitleDecoderFactory, OnDataChange {
    private static final String TAG = SentenceSubtitles.class.getSimpleName();
    private final SentenceSubtitlesData mData;
    // Written on the playback thread once there's a player (see #onDataChange), and read there. The decoders read it too, to make
    // the sentences ahead.
    private volatile boolean mIsEnabled;
    // What the playback thread was last told, or is about to be (see #onDataChange)
    private boolean mIsEnabledSent;
    // The subtitle file decoded last: its sentences are made off the playback thread when the setting is turned on
    @Nullable
    private volatile Switch mLastSwitch;
    @Nullable
    private TextRenderer mRenderer;
    @Nullable
    private ExoPlayer mPlayer;

    public SentenceSubtitles(Context context) {
        mData = SentenceSubtitlesData.instance(context);
        mIsEnabled = mData.isEnabled();
        mIsEnabledSent = mIsEnabled;
        mData.setOnChange(this);
    }

    /**
     * The player's text renderer: once, as the player is built
     */
    public TextRenderer createRenderer(TextOutput output, @Nullable Looper outputLooper) {
        mRenderer = new TextRenderer(output, outputLooper, this);
        return mRenderer;
    }

    /**
     * The player the renderer was built for: a change of the setting shows on its video at once
     */
    public void setPlayer(@Nullable ExoPlayer player) {
        mPlayer = player;
    }

    @Override
    public void onDataChange() {
        boolean enabled = mData.isEnabled();

        if (enabled == mIsEnabledSent) {
            return;
        }

        mIsEnabledSent = enabled;

        if (mPlayer == null || mRenderer == null) {
            mIsEnabled = enabled;
            return;
        }

        Switch last = mLastSwitch;

        if (enabled && last != null) {
            // Made here first: the playback thread would hold the video up for them (a long video's file has thousands of cues)
            RxHelper.runAsyncUser(last::prepare, error -> sendEnabled(), this::sendEnabled);
        } else {
            sendEnabled();
        }
    }

    /**
     * Tells the playback thread the setting as it is now: it may have changed again while the sentences were made
     */
    private void sendEnabled() {
        if (mPlayer == null || mRenderer == null) {
            return;
        }

        boolean enabled = mIsEnabledSent;

        // A released player drops it
        mPlayer.createMessage(mRenderer)
                .setType(TextRenderer.MSG_REFRESH)
                .setPayload((Runnable) () -> mIsEnabled = enabled)
                .send();
    }

    @Override
    public boolean supportsFormat(Format format) {
        return SubtitleDecoderFactory.DEFAULT.supportsFormat(format);
    }

    @Override
    public SubtitleDecoder createDecoder(Format format) {
        SubtitleDecoder decoder = SubtitleDecoderFactory.DEFAULT.createDecoder(format);

        if (decoder instanceof SimpleSubtitleDecoder && isWholeFile(format)) {
            ((SimpleSubtitleDecoder) decoder).setTransformer(new Transformer());
        }

        return decoder;
    }

    /**
     * A video's subtitles, a WebVTT or TTML file (see YouTubeMPDBuilder, DashManifestParser2). A live stream's are mp4 segments.
     */
    private static boolean isWholeFile(Format format) {
        return MimeTypes.TEXT_VTT.equals(format.containerMimeType) || MimeTypes.APPLICATION_TTML.equals(format.containerMimeType);
    }

    /**
     * A decoder's. The file is decoded again after each seek: its sentences are kept for that.
     */
    private final class Transformer implements SubtitleTransformer, Switch.Source {
        // Of the subtitle the sentences are of
        @Nullable
        private long[] mEventTimesUs;
        @Nullable
        private Subtitle mSentences;

        @Override
        public Subtitle transform(Subtitle subtitle) {
            Switch result = new Switch(subtitle, this);

            if (mIsEnabled) {
                // Here, not on the playback thread
                result.prepare();
            }

            mLastSwitch = result;

            return result;
        }

        @Override
        public boolean isEnabled() {
            return mIsEnabled;
        }

        @Nullable
        @Override
        public synchronized Subtitle getSentences(Subtitle subtitle) {
            long[] eventTimesUs = new long[subtitle.getEventTimeCount()];

            for (int i = 0; i < eventTimesUs.length; i++) {
                eventTimesUs[i] = subtitle.getEventTime(i);
            }

            if (Arrays.equals(eventTimesUs, mEventTimesUs)) {
                return mSentences;
            }

            Sentences.Timeline sentences = null;

            try {
                sentences = Sentences.build(subtitle);
                Log.d(TAG, "%s sentences of %s subtitle events", sentences != null ? sentences.getSentenceCount() : 0, eventTimesUs.length);
            } catch (RuntimeException e) {
                // Shown as they come instead
                Log.e(TAG, "Can't make the sentences", e);
            }

            mEventTimesUs = eventTimesUs;
            mSentences = sentences;

            return sentences;
        }
    }

    /**
     * A decoded subtitle file, shown as it is or one sentence at a time, as the setting is when the player looks at it. Used on the
     * playback thread but for #prepare: the decoder calls it before it hands the file on, and a turn of the setting on calls it
     * ahead (see #onDataChange).
     */
    static final class Switch implements Subtitle {
        interface Source {
            boolean isEnabled();

            /**
             * @return null when there are none
             */
            @Nullable
            Subtitle getSentences(Subtitle subtitle);
        }

        private final Subtitle mOriginal;
        private final Source mSource;
        @Nullable
        private Subtitle mSentences;
        private boolean mIsPrepared;

        Switch(@NonNull Subtitle original, @NonNull Source source) {
            mOriginal = original;
            mSource = source;
        }

        synchronized void prepare() {
            if (!mIsPrepared) {
                mSentences = mSource.getSentences(mOriginal);
                mIsPrepared = true;
            }
        }

        private Subtitle getCurrent() {
            if (!mSource.isEnabled()) {
                return mOriginal;
            }

            // Made the first time the setting is on for this file, unless already. Read after it, so whichever thread made them.
            prepare();
            Subtitle sentences = mSentences;

            return sentences != null ? sentences : mOriginal;
        }

        @Override
        public int getNextEventTimeIndex(long timeUs) {
            return getCurrent().getNextEventTimeIndex(timeUs);
        }

        @Override
        public int getEventTimeCount() {
            return getCurrent().getEventTimeCount();
        }

        @Override
        public long getEventTime(int index) {
            return getCurrent().getEventTime(index);
        }

        @Override
        public List<Cue> getCues(long timeUs) {
            return getCurrent().getCues(timeUs);
        }
    }
}
