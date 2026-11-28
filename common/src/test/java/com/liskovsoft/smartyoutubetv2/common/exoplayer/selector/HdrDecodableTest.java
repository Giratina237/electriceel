package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;

import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.util.MimeTypes;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Whether the HDR formats are shown: a hardware decoder lists VP9 profile 2 or AV1 Main 10 (see
 * TrackSelectorUtil#isVP9HdrDecodable). The decoders are shield2's (a darcy on Android 11, dumpsys media.player).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class HdrDecodableTest {
    private static final int VP9_LEVEL_5 = 0x100;
    private static final int VP9_LEVEL_51 = 0x200;
    private static final int AV1_LEVEL_51 = 0x1000;

    @Test
    public void shieldVP9_profile0Only_noHdr() {
        MediaCodecInfo nvidia = decoder("OMX.Nvidia.vp9.decode", MimeTypes.VIDEO_VP9, profile(CodecProfileLevel.VP9Profile0, VP9_LEVEL_51));
        // Lists profile 2, but is software: 2048x2048 at most, in 8-bit colours
        MediaCodecInfo google = decoder("OMX.google.vp9.decoder", MimeTypes.VIDEO_VP9,
                profile(CodecProfileLevel.VP9Profile0, VP9_LEVEL_5),
                profile(CodecProfileLevel.VP9Profile2, VP9_LEVEL_5),
                profile(CodecProfileLevel.VP9Profile2HDR, VP9_LEVEL_5),
                profile(CodecProfileLevel.VP9Profile2HDR10Plus, VP9_LEVEL_5));

        assertFalse(TrackSelectorUtil.isVP9HdrDecodable(Arrays.asList(nvidia, google)));
    }

    @Test
    public void hardwareVP9Profile2_hdr() {
        assertTrue(TrackSelectorUtil.isVP9HdrDecodable(Collections.singletonList(
                decoder("c2.amlogic.vp9.decoder", MimeTypes.VIDEO_VP9, profile(CodecProfileLevel.VP9Profile2, VP9_LEVEL_51)))));
        assertTrue(TrackSelectorUtil.isVP9HdrDecodable(Collections.singletonList(
                decoder("c2.amlogic.vp9.decoder", MimeTypes.VIDEO_VP9, profile(CodecProfileLevel.VP9Profile2HDR, VP9_LEVEL_51)))));
    }

    /**
     * It says nothing: the formats are shown, as before
     */
    @Test
    public void hardwareWithoutProfiles_hdr() {
        assertTrue(TrackSelectorUtil.isVP9HdrDecodable(Collections.singletonList(decoder("OMX.vendor.vp9.decoder", MimeTypes.VIDEO_VP9))));
    }

    @Test
    public void decodersUnread_hdr() {
        assertTrue(TrackSelectorUtil.isVP9HdrDecodable(null));
        assertTrue(TrackSelectorUtil.isAV1HdrDecodable(null));
    }

    /**
     * A Shield has no AV1 decoder
     */
    @Test
    public void noDecoder_noHdr() {
        assertFalse(TrackSelectorUtil.isAV1HdrDecodable(Collections.emptyList()));
    }

    @Test
    public void av1_main10() {
        assertFalse(TrackSelectorUtil.isAV1HdrDecodable(Arrays.asList(
                decoder("c2.amlogic.av1.decoder", MimeTypes.VIDEO_AV1, profile(CodecProfileLevel.AV1ProfileMain8, AV1_LEVEL_51)),
                decoder("c2.android.av1.decoder", MimeTypes.VIDEO_AV1, profile(CodecProfileLevel.AV1ProfileMain10, AV1_LEVEL_51)))));
        assertTrue(TrackSelectorUtil.isAV1HdrDecodable(Collections.singletonList(
                decoder("c2.amlogic.av1.decoder", MimeTypes.VIDEO_AV1, profile(CodecProfileLevel.AV1ProfileMain10HDR10, AV1_LEVEL_51)))));
    }

    private static MediaCodecInfo decoder(String name, String mimeType, CodecProfileLevel... profileLevels) {
        CodecCapabilities capabilities = CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(mimeType, 3840, 2160))
                .setColorFormats(new int[] {CodecCapabilities.COLOR_FormatYUV420Flexible})
                .setProfileLevels(profileLevels)
                .build();

        return MediaCodecInfo.newInstance(name, mimeType, mimeType, capabilities, false, false);
    }

    private static CodecProfileLevel profile(int profile, int level) {
        CodecProfileLevel result = new CodecProfileLevel();
        result.profile = profile;
        result.level = level;
        return result;
    }
}
