package com.google.android.exoplayer2.source.sabr.manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaSubtitle;
import com.google.protobuf.ByteString;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SabrManifestParserTest {
    @Test
    public void preservesVisitorCookieAndExternalCaptionUrl() {
        MediaItemFormatInfo formatInfo = mock(MediaItemFormatInfo.class);
        MediaItemFormatInfo.ClientInfo clientInfo = mock(MediaItemFormatInfo.ClientInfo.class);
        when(clientInfo.getClientName()).thenReturn("WEB");
        when(clientInfo.getClientVersion()).thenReturn("test");
        when(clientInfo.getOsName()).thenReturn("");
        when(clientInfo.getOsVersion()).thenReturn("");
        when(formatInfo.getClientInfo()).thenReturn(clientInfo);
        when(formatInfo.getLengthSeconds()).thenReturn("60");
        when(formatInfo.getMediaDurationMs()).thenAnswer(invocation ->
                MediaItemFormatInfo.getMediaDurationMs(formatInfo.getAdaptiveFormats(), formatInfo.getLengthSeconds()));
        when(formatInfo.getVideoId()).thenReturn("test");
        when(formatInfo.getServerAbrStreamingUrl())
                .thenReturn("https://media.example/videoplayback");
        when(formatInfo.getAdaptiveFormats()).thenReturn(Collections.emptyList());
        when(formatInfo.getVisitorCookie()).thenReturn("VISITOR_INFO1_LIVE=test");
        MediaSubtitle subtitle = mock(MediaSubtitle.class);
        when(subtitle.getMimeType()).thenReturn(MimeTypes.TEXT_VTT);
        when(subtitle.getLanguageCode()).thenReturn("en");
        when(subtitle.getVssId()).thenReturn("en");
        when(subtitle.getBaseUrl()).thenReturn("https://www.youtube.com/api/timedtext?lang=en");
        when(formatInfo.getSubtitles()).thenReturn(Collections.singletonList(subtitle));

        SabrManifest manifest = new SabrManifestParser().parse(formatInfo);

        assertEquals("VISITOR_INFO1_LIVE=test", manifest.visitorCookie);
        assertEquals(60_000, manifest.durationMs);
        AdaptationSet captions = manifest.getPeriod(0).adaptationSets.get(0);
        assertEquals(C.TRACK_TYPE_TEXT, captions.type);
        Representation representation = captions.representations.get(0);
        assertEquals(subtitle.getBaseUrl(), representation.baseUrl);
        assertEquals(MimeTypes.TEXT_VTT, representation.format.containerMimeType);
        assertEquals(1, representation.getIndex().getSegmentCount(60_000_000));
    }

    /**
     * A video's length is its media's, to the ms: the longest of its formats. Its whole seconds (1972) are past the end
     * (9lKpEOEbr0U), where a stopped video's player didn't get ready
     */
    @Test
    public void aVideoLastsAsLongAsItsLongestFormat() {
        MediaItemFormatInfo formatInfo = videoFormatInfo("1972", false, format(1_971_719), format(1_971_792), format(1_971_741));

        SabrManifest manifest = new SabrManifestParser().parse(formatInfo);

        assertEquals(1_971_792, manifest.durationMs);
    }

    /**
     * Formats without a length: the whole seconds, as before
     */
    @Test
    public void aVideoWithoutFormatLengthsLastsItsWholeSeconds() {
        MediaItemFormatInfo formatInfo = videoFormatInfo("1972", false, format(0), format(-1));

        assertEquals(1_972_000, new SabrManifestParser().parse(formatInfo).durationMs);
    }

    /**
     * A live stream's formats aren't its length: it spans the DASH live manifest's 48 hours from its start segment. Unset, the player
     * had no duration (SABR representations have no index), and the app's live position is the player's duration less a buffer.
     */
    @Test
    public void aLiveStreamSpansTheDashLiveWindow() {
        MediaItemFormatInfo formatInfo = videoFormatInfo("0", true, format(1_971_792));

        SabrManifest manifest = new SabrManifestParser().parse(formatInfo);

        assertEquals(LIVE_WINDOW_MS, manifest.durationMs);
        assertTrue(manifest.isLive());
    }

    /**
     * The server's times are the media's: less the start segment's (as the DASH live manifest's), they're the timeline's
     */
    @Test
    public void aLiveStreamsTimelineStartsAtItsStartSegment() {
        MediaItemFormatInfo formatInfo = videoFormatInfo("0", true, liveAudio(2));
        when(formatInfo.getStartSegmentTimeUs()).thenReturn(START_SEGMENT_TIME_US);
        when(formatInfo.getStartSegmentNum()).thenReturn(391_522);
        when(formatInfo.getSegmentDurationUs()).thenReturn(4_999_839);

        SabrManifest manifest = new SabrManifestParser().parse(formatInfo);

        Representation audio = manifest.getPeriod(0).adaptationSets.get(0).representations.get(0);
        assertEquals(START_SEGMENT_TIME_US, audio.presentationTimeOffsetUs);
        assertEquals(2, manifest.liveSegmentTargetDurationSec);
    }

    @Test
    public void aLiveSegmentsTargetIsTheFormatsElseTheHeadsAverage() {
        MediaItemFormatInfo average = videoFormatInfo("0", true, liveAudio(0));
        when(average.getSegmentDurationUs()).thenReturn(999_986);
        assertEquals(1, new SabrManifestParser().parse(average).liveSegmentTargetDurationSec);

        MediaItemFormatInfo none = videoFormatInfo("0", true, liveAudio(0));
        assertEquals(5, new SabrManifestParser().parse(none).liveSegmentTargetDurationSec);
    }

    /**
     * A finished stream is a video: its length, no live timeline
     */
    @Test
    public void aFinishedStreamIsAVideo() {
        MediaItemFormatInfo formatInfo = videoFormatInfo("2031", false, format(2_030_910));

        SabrManifest manifest = new SabrManifestParser().parse(formatInfo);

        assertEquals(2_030_910, manifest.durationMs);
        assertEquals(SabrManifest.NOT_LIVE, manifest.liveSegmentTargetDurationSec);
    }

    /**
     * Fetched again before they expire (SmartTube #6478): the same client's streams, with each format as it was, are played on with.
     * The next request of a track goes to their URL, with their config and poToken.
     */
    @Test
    public void streamsFetchedAgainWithTheSameFormatsArePlayedOnWith() {
        SabrManifest manifest = new SabrManifestParser().parse(
                sabrFormatInfo("https://rr1---sn-old.googlevideo.com/videoplayback", "b2xk", "b2xk", "WEB", audio("1700000000000000")));
        SabrStream stream = manifest.getSabrStream(C.TRACK_TYPE_AUDIO);

        assertTrue(SabrManifestParser.refresh(manifest,
                sabrFormatInfo("https://rr2---sn-new.googlevideo.com/videoplayback", "bmV3", "cG90", "WEB", audio("1700000000000000"))));

        assertTrue(stream.getUrl().startsWith("https://rr2---sn-new.googlevideo.com/"));
        assertTrue(manifest.getRequestUrl(C.TRACK_TYPE_AUDIO).startsWith("https://rr2---sn-new.googlevideo.com/"));
        VideoPlaybackAbrRequest request = manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, false);
        assertEquals(bytes("bmV3"), request.getVideoPlaybackUstreamerConfig());
        assertEquals(bytes("cG90"), request.getStreamerContext().getPoToken());
    }

    /**
     * Another format (here its last modified time), another client, another video, or a live stream's: the requests name the formats
     * and the client, the old streams stay
     */
    @Test
    public void streamsFetchedAgainThatDontFitAreNotPlayedOnWith() {
        SabrManifest manifest = new SabrManifestParser().parse(
                sabrFormatInfo("https://rr1---sn-old.googlevideo.com/videoplayback", "b2xk", "b2xk", "WEB", audio("1700000000000000")));
        SabrStream stream = manifest.getSabrStream(C.TRACK_TYPE_AUDIO);
        MediaItemFormatInfo otherVideo = sabrFormatInfo("https://rr2---sn-new.googlevideo.com/videoplayback", "bmV3", "cG90", "WEB",
                audio("1700000000000000"));
        when(otherVideo.getVideoId()).thenReturn("other");
        MediaItemFormatInfo live = sabrFormatInfo("https://rr2---sn-new.googlevideo.com/videoplayback", "bmV3", "cG90", "WEB",
                audio("1700000000000000"));
        when(live.isLive()).thenReturn(true);

        assertFalse(SabrManifestParser.refresh(manifest,
                sabrFormatInfo("https://rr2---sn-new.googlevideo.com/videoplayback", "bmV3", "cG90", "WEB", audio("1800000000000000"))));
        assertFalse(SabrManifestParser.refresh(manifest,
                sabrFormatInfo("https://rr2---sn-new.googlevideo.com/videoplayback", "bmV3", "cG90", "TVHTML5", audio("1700000000000000"))));
        assertFalse(SabrManifestParser.refresh(manifest, otherVideo));
        assertFalse(SabrManifestParser.refresh(manifest, live));

        assertTrue(stream.getUrl().startsWith("https://rr1---sn-old.googlevideo.com/"));
        assertEquals(bytes("b2xk"), manifest.createVideoPlaybackAbrRequest(C.TRACK_TYPE_AUDIO, false).getVideoPlaybackUstreamerConfig());
    }

    private static MediaItemFormatInfo sabrFormatInfo(String url, String ustreamerConfig, String poToken, String client,
                                                      MediaFormat... formats) {
        MediaItemFormatInfo formatInfo = videoFormatInfo("60", false, formats);
        MediaItemFormatInfo.ClientInfo clientInfo = mock(MediaItemFormatInfo.ClientInfo.class);
        when(clientInfo.getClientName()).thenReturn(client);
        when(clientInfo.getClientVersion()).thenReturn("test");
        when(clientInfo.getOsName()).thenReturn("");
        when(clientInfo.getOsVersion()).thenReturn("");
        when(formatInfo.getClientInfo()).thenReturn(clientInfo);
        when(formatInfo.getServerAbrStreamingUrl()).thenReturn(url);
        when(formatInfo.getVideoPlaybackUstreamerConfig()).thenReturn(ustreamerConfig);
        when(formatInfo.getPoToken()).thenReturn(poToken);

        return formatInfo;
    }

    /**
     * An audio format of a video, as TV answers it: last modified at the time given
     */
    private static MediaFormat audio(String lmt) {
        MediaFormat format = mock(MediaFormat.class);
        when(format.getITag()).thenReturn("140");
        when(format.getMimeType()).thenReturn("audio/mp4; codecs=\"mp4a.40.2\"");
        when(format.getLmt()).thenReturn(lmt);
        when(format.getXtags()).thenReturn("CggKA2RyYxIBMQ");
        when(format.getGlobalSegmentList()).thenReturn(null); // a mock's empty list is an OTF format's
        when(format.getSegmentUrlList()).thenReturn(null);

        return format;
    }

    private static ByteString bytes(String base64) {
        return ByteString.copyFrom(Base64.decode(base64, Base64.URL_SAFE));
    }

    private static MediaItemFormatInfo videoFormatInfo(String lengthSeconds, boolean isLive, MediaFormat... formats) {
        MediaItemFormatInfo formatInfo = mock(MediaItemFormatInfo.class);
        MediaItemFormatInfo.ClientInfo clientInfo = mock(MediaItemFormatInfo.ClientInfo.class);
        when(clientInfo.getClientName()).thenReturn("WEB");
        when(clientInfo.getClientVersion()).thenReturn("test");
        when(clientInfo.getOsName()).thenReturn("");
        when(clientInfo.getOsVersion()).thenReturn("");
        when(formatInfo.getClientInfo()).thenReturn(clientInfo);
        when(formatInfo.getLengthSeconds()).thenReturn(lengthSeconds);
        when(formatInfo.isLive()).thenReturn(isLive);
        when(formatInfo.getMediaDurationMs()).thenAnswer(invocation ->
                MediaItemFormatInfo.getMediaDurationMs(formatInfo.getAdaptiveFormats(), formatInfo.getLengthSeconds()));
        when(formatInfo.getVideoId()).thenReturn("test");
        when(formatInfo.getServerAbrStreamingUrl()).thenReturn("https://media.example/videoplayback");
        when(formatInfo.getAdaptiveFormats()).thenReturn(Arrays.asList(formats));

        return formatInfo;
    }

    private static final long LIVE_WINDOW_MS = 48 * 60 * 60 * 1_000L;
    private static final long START_SEGMENT_TIME_US = 1_957_547_035_080L;

    /**
     * A live stream's audio format, as TV answers it (no URL, no index range)
     */
    private static MediaFormat liveAudio(int targetDurationSec) {
        MediaFormat format = mock(MediaFormat.class);
        when(format.getITag()).thenReturn("140");
        when(format.getMimeType()).thenReturn("audio/mp4; codecs=\"mp4a.40.2\"");
        when(format.getTargetDurationSec()).thenReturn(targetDurationSec);
        when(format.getGlobalSegmentList()).thenReturn(null); // a mock's empty list is an OTF format's
        when(format.getSegmentUrlList()).thenReturn(null);

        return format;
    }

    /**
     * Its approxDurationMs only: not a DASH format the manifest takes
     */
    private static MediaFormat format(int approxDurationMs) {
        MediaFormat format = mock(MediaFormat.class);
        when(format.getApproxDurationMs()).thenReturn(approxDurationMs);

        return format;
    }
}
