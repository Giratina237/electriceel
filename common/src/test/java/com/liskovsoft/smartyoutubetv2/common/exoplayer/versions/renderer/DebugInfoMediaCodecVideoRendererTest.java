package com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.renderer;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererConfiguration;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.source.EmptySampleStream;
import com.google.android.exoplayer2.util.MimeTypes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Of a tunneled decoder, what it was given against the position: it shows the frames itself, so processOutputBuffer, which tells the
 * frames decoded to get there, never runs
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DebugInfoMediaCodecVideoRendererTest {
    private static final Format FORMAT = Format.createVideoSampleFormat(
            null, MimeTypes.VIDEO_VP9, null, Format.NO_VALUE, Format.NO_VALUE, 1920, 1080, 60, null, null);

    /**
     * H5B64KBE, Y1D66CNW: 4 of 7 tunneled starts sent to a position froze, and how far each decoder had to decode to get there
     * couldn't be told. The first sample given after the position is set is the keyframe before it.
     */
    @Test
    public void howFarATunneledDecoderHadToGetToThePositionIsTold() throws ExoPlaybackException {
        DebugInfoMediaCodecVideoRenderer renderer = newRenderer();
        renderer.enable(new RendererConfiguration(1), new Format[] {FORMAT}, new EmptySampleStream(), 1_285_300_000, false, 0);

        assertNull(renderer.getTunneledFrames().keyframeBehindMs);

        renderer.onQueueInputBuffer(sample(1_280_000_000));
        renderer.onQueueInputBuffer(sample(1_280_016_000));
        assertEquals(Long.valueOf(5_300), renderer.getTunneledFrames().keyframeBehindMs);

        // Sent elsewhere: from the keyframe before that
        renderer.resetPosition(60_000_000);
        assertNull(renderer.getTunneledFrames().keyframeBehindMs);

        renderer.onQueueInputBuffer(sample(58_000_000));
        assertEquals(Long.valueOf(2_000), renderer.getTunneledFrames().keyframeBehindMs);
    }

    private static DebugInfoMediaCodecVideoRenderer newRenderer() {
        return new DebugInfoMediaCodecVideoRenderer(RuntimeEnvironment.getApplication(), MediaCodecSelector.DEFAULT, 0, null, false, false,
                null, null, 0);
    }

    private static DecoderInputBuffer sample(long timeUs) {
        DecoderInputBuffer buffer = new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED);
        buffer.timeUs = timeUs;
        buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME);

        return buffer;
    }
}
