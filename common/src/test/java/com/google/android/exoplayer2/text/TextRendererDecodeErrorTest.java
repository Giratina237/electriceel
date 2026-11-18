package com.google.android.exoplayer2.text;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererConfiguration;
import com.google.android.exoplayer2.source.EmptySampleStream;
import com.google.android.exoplayer2.util.MimeTypes;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A subtitle that fails to decode (ExoPlayer 2.11.4 #6885 and 2.12.0 #7590, backported to the vendored 2.10.6). Here, not with the
 * vendored ExoPlayer: that module's tests don't compile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TextRendererDecodeErrorTest {
    private final List<FakeDecoder> mDecoders = new ArrayList<>();
    private TextRenderer mRenderer;

    @Before
    public void setUp() throws ExoPlaybackException {
        mRenderer = new TextRenderer(cues -> {}, null, new SubtitleDecoderFactory() {
            @Override
            public boolean supportsFormat(Format format) {
                return true;
            }

            @Override
            public SubtitleDecoder createDecoder(Format format) {
                FakeDecoder decoder = new FakeDecoder(mDecoders.isEmpty());
                mDecoders.add(decoder);
                return decoder;
            }
        });
        Format format = Format.createTextSampleFormat(null, MimeTypes.TEXT_VTT, 0, null);
        // A stream at its end: what the first decoder is given is all there is, as a whole subtitle file followed by its end
        mRenderer.enable(RendererConfiguration.DEFAULT, new Format[] {format}, new EmptySampleStream(), 0, false, 0);
        mRenderer.setCurrentStreamFinal();
        mRenderer.start();
    }

    /**
     * Not a player error, and a new decoder: the one that failed keeps its error
     */
    @Test
    public void aDecodeError_replacesTheDecoder() throws ExoPlaybackException {
        mRenderer.render(0, 0);
        mRenderer.render(0, 0);

        assertEquals(2, mDecoders.size());
        assertTrue(mDecoders.get(0).mReleased);
        assertFalse(mDecoders.get(1).mReleased);
    }

    /**
     * The new decoder is given the end too: the video ends, it doesn't wait for subtitles that never come
     */
    @Test
    public void aDecodeErrorAfterTheLastSample_stillEnds() throws ExoPlaybackException {
        for (int i = 0; i < 10 && !mRenderer.isEnded(); i++) {
            mRenderer.render(0, 0);
        }

        assertEquals(2, mDecoders.size());
        assertTrue(mDecoders.get(1).mEndOfStreamQueued);
        assertTrue(mRenderer.isEnded());
    }

    /**
     * Its output is the end of the stream once it's been given it, unless it fails on what it was given
     */
    private static final class FakeDecoder implements SubtitleDecoder {
        private final boolean mFails;
        private boolean mEndOfStreamQueued;
        private boolean mReleased;

        private FakeDecoder(boolean fails) {
            mFails = fails;
        }

        @Override
        public String getName() {
            return "FakeDecoder";
        }

        @Override
        public void setPositionUs(long positionUs) {
        }

        @Override
        public SubtitleInputBuffer dequeueInputBuffer() {
            return mEndOfStreamQueued ? null : new SubtitleInputBuffer();
        }

        @Override
        public void queueInputBuffer(SubtitleInputBuffer inputBuffer) {
            mEndOfStreamQueued |= inputBuffer.isEndOfStream();
        }

        @Override
        public SubtitleOutputBuffer dequeueOutputBuffer() throws SubtitleDecoderException {
            if (!mEndOfStreamQueued) {
                return null;
            }
            if (mFails) {
                throw new SubtitleDecoderException("malformed cue");
            }
            SubtitleOutputBuffer buffer = new SubtitleOutputBuffer() {
                @Override
                public void release() {
                }
            };
            buffer.setFlags(C.BUFFER_FLAG_END_OF_STREAM);
            return buffer;
        }

        @Override
        public void flush() {
        }

        @Override
        public void release() {
            mReleased = true;
        }
    }
}
