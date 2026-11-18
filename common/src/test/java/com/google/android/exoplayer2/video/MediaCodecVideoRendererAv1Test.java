package com.google.android.exoplayer2.video;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.util.MimeTypes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * AV1's max input size (ExoPlayer 2.18.0, backported to the vendored 2.10.6). Here, not with the vendored ExoPlayer: that module's
 * tests don't compile. In ExoPlayer's package: getCodecMaxValues is protected.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MediaCodecVideoRendererAv1Test {
    /**
     * Sized like VP8, a min compression of 2 over 4:2:0 frames, not left to the decoder's default
     */
    @Test
    public void av1_hasAMaxInputSize() {
        MediaCodecVideoRenderer renderer = new MediaCodecVideoRenderer(RuntimeEnvironment.getApplication(), MediaCodecSelector.DEFAULT);
        MediaCodecInfo codecInfo =
                MediaCodecInfo.newInstance("c2.test.av1.decoder", MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_AV1, null, false, false);
        Format format = Format.createVideoSampleFormat(
                null, MimeTypes.VIDEO_AV1, null, Format.NO_VALUE, Format.NO_VALUE, 1920, 1080, 30, null, null);

        MediaCodecVideoRenderer.CodecMaxValues values = renderer.getCodecMaxValues(codecInfo, format, new Format[] {format});

        assertEquals(1920 * 1080 * 3 / 4, values.inputSize);
    }
}
