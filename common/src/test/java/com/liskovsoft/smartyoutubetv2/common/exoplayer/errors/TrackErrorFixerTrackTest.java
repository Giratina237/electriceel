package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import android.net.Uri;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.MediaSourceEventListener.MediaLoadData;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.PlayerFailure;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

/**
 * K067TC3S #7–#18: 429s of SABR requests and of subtitles were all "other", since only a DASH segment's URL names its mime type.
 * The track is the load's.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TrackErrorFixerTrackTest {
    private static final String SABR_URL = "https://rr3---sn-abc.googlevideo.com/videoplayback?sabr=1&rn=3";
    private static final String TIMEDTEXT_URL = "https://www.youtube.com/api/timedtext?v=x&lang=en&tlang=ar";

    @Test
    public void aLoadIsOfItsTracksType() {
        assertEquals(PlayerFailure.TRACK_VIDEO, TrackErrorFixer.trackOf(load(C.TRACK_TYPE_VIDEO, null), SABR_URL));
        assertEquals(PlayerFailure.TRACK_AUDIO, TrackErrorFixer.trackOf(load(C.TRACK_TYPE_AUDIO, null), SABR_URL));
        assertEquals(PlayerFailure.TRACK_TEXT, TrackErrorFixer.trackOf(load(C.TRACK_TYPE_TEXT, null), TIMEDTEXT_URL));
        // Muxed (the URL list's)
        assertEquals(PlayerFailure.TRACK_VIDEO, TrackErrorFixer.trackOf(load(C.TRACK_TYPE_DEFAULT, null), SABR_URL));
    }

    /**
     * A sideloaded subtitle's load has no track type (see SingleSampleMediaPeriod), its format has
     */
    @Test
    public void aLoadOfNoTrackTypeIsOfItsFormats() {
        Format vtt = Format.createTextSampleFormat(null, MimeTypes.TEXT_VTT, 0, "ar");

        assertEquals(PlayerFailure.TRACK_TEXT, TrackErrorFixer.trackOf(load(C.TRACK_TYPE_UNKNOWN, vtt), TIMEDTEXT_URL));
    }

    @Test
    public void aLoadNotKnownGoesByItsUrl() {
        assertEquals(PlayerFailure.TRACK_AUDIO, TrackErrorFixer.trackOf(null, "https://x.googlevideo.com/videoplayback/mime/audio%2Fwebm"));
        assertEquals(PlayerFailure.TRACK_OTHER, TrackErrorFixer.trackOf(load(C.TRACK_TYPE_UNKNOWN, null), SABR_URL));
    }

    /**
     * The app's own manifests name it in the query (see YouTubeMPDBuilder): an audio segment's 404 swapped the video (Q19MW058 #2)
     */
    @Test
    public void aLoadNotKnownGoesByItsUrlsQueryToo() {
        assertEquals(PlayerFailure.TRACK_AUDIO, TrackErrorFixer.trackOf(null,
                "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&itag=251&mime=audio%2Fwebm&clen=1"));
        assertEquals(PlayerFailure.TRACK_VIDEO, TrackErrorFixer.trackOf(null,
                "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1&itag=248&mime=video%2Fwebm&clen=1"));
    }

    /**
     * Another family of the same size: AVC's 720p profile isn't another codec of its 1080p one (Q19MW058 #2 got 720p)
     */
    @Test
    public void anotherVideoCodecIsAnotherFamilyOfTheSameSize() {
        Format avc1080 = video("avc1.640028", 1920, 1080);

        assertFalse(TrackErrorFixer.isOtherCodec(avc1080, video("avc1.4d401f", 1280, 720), false));
        assertTrue(TrackErrorFixer.isOtherCodec(avc1080, video("vp9", 1920, 1080), false));
        assertFalse(TrackErrorFixer.isOtherCodec(avc1080, video("av01.0.05M.08", 1280, 720), false));
        assertFalse(TrackErrorFixer.isOtherCodec(video("vp9", 1920, 1080), video("vp09.00.40.08", 1920, 1080), false));
    }

    /**
     * An audio has no size: another codec string, e.g. AAC's HE profile for its LC one, as before
     */
    @Test
    public void anotherAudioCodecIsAnotherCodecString() {
        Format aac = Format.createAudioSampleFormat("140", MimeTypes.AUDIO_AAC, "mp4a.40.2", 128_000, Format.NO_VALUE, 2, 44_100, null,
                null, 0, "en");

        assertTrue(TrackErrorFixer.isOtherCodec(aac, Format.createAudioSampleFormat("139", MimeTypes.AUDIO_AAC, "mp4a.40.5", 48_000,
                Format.NO_VALUE, 2, 22_050, null, null, 0, "en"), true));
        assertFalse(TrackErrorFixer.isOtherCodec(aac, aac, true));
    }

    /**
     * A regular video's 503 (97MSK8B6: every format of it answered 503), or a load of another track (e.g. subtitles), isn't fixed by
     * another codec: nothing's swapped
     */
    @Test
    public void aRegularVideosServerErrorOrASubtitlesErrorSwapsNothing() {
        TrackErrorFixer fixer = new TrackErrorFixer(null);
        fixer.setLiveCheck(() -> false);

        assertFalse(fixer.fixError(httpError(503), PlayerFailure.TRACK_VIDEO));
        assertFalse(fixer.fixError(httpError(500), PlayerFailure.TRACK_AUDIO));
        assertFalse(fixer.fixError(httpError(404), PlayerFailure.TRACK_TEXT));
        assertFalse(fixer.fixError(httpError(404), PlayerFailure.TRACK_OTHER));
        assertFalse(fixer.fixError(httpError(403), PlayerFailure.TRACK_VIDEO));
        assertFalse(fixer.fixError(new java.io.IOException("Response code: 404"), PlayerFailure.TRACK_VIDEO));
    }

    private static Format video(String codecs, int width, int height) {
        return Format.createVideoSampleFormat(null, MimeTypes.VIDEO_VP9, codecs, Format.NO_VALUE, Format.NO_VALUE, width, height, 30, null,
                null);
    }

    private static InvalidResponseCodeException httpError(int code) {
        return new InvalidResponseCodeException(code, null, Collections.emptyMap(),
                new DataSpec(Uri.parse("https://rr3---sn-abc.googlevideo.com/videoplayback?mime=video%2Fwebm")));
    }

    /**
     * The domain only: a media server's name says where the user is
     */
    @Test
    public void aHostIsItsDomain() {
        assertEquals("googlevideo.com", PlayerFailure.describeHost("rr3---sn-4g5e6nsz.googlevideo.com"));
        assertEquals("youtube.com", PlayerFailure.describeHost("www.youtube.com"));
        assertEquals("other", PlayerFailure.describeHost("example.org"));
        assertEquals("other", PlayerFailure.describeHost("notgooglevideo.com"));
        assertNull(PlayerFailure.describeHost(null));
    }

    private static MediaLoadData load(int trackType, Format format) {
        return new MediaLoadData(C.DATA_TYPE_MEDIA, trackType, format, C.SELECTION_REASON_UNKNOWN, null, 0, 0);
    }
}
