package com.google.android.exoplayer2.source.sabr;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.source.sabr.parser.misc.EnabledTrackTypes;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPartId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.LiveMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrRedirect;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.youtubeapi.videoinfo.SabrLiveHead;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * A live stream's head over SABR, for one whose formats have no URLs: the player's first request, and the live metadata its response
 * starts with
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SabrLiveHeadProtocolTest {
    private static final String PO_TOKEN = "dG9rZW4";
    private static final String USTREAMER_CONFIG = "CgQIARAB";
    private static final int HEAD_SEQUENCE = 408_802;
    private static final long HEAD_TIME_MS = 2_043_944_253L;
    private static final long WALL_TIME_MS = 1_794_723_091_761L;

    @Test
    public void theRequestAsksForTheAudioAndNamesTheVideo() throws Exception {
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(SabrLiveHeadProtocol.createRequestBody(
                USTREAMER_CONFIG, PO_TOKEN, clientInfo(), new SabrLiveHead.Format(140, -1, null, 144_000),
                new SabrLiveHead.Format(278, -1, null, 95_000)));

        assertEquals(EnabledTrackTypes.AUDIO_ONLY, request.getClientAbrState().getEnabledTrackTypesBitfield());
        assertEquals(0, request.getClientAbrState().getPlayerTimeMs());
        assertEquals(144_000, request.getClientAbrState().getBandwidthEstimate());
        assertEquals(140, request.getPreferredAudioFormatIds(0).getItag());
        assertEquals(278, request.getPreferredVideoFormatIds(0).getItag());
        assertEquals(0, request.getSelectedFormatIdsCount());
        assertArrayEquals(Base64.decode(PO_TOKEN, Base64.URL_SAFE), request.getStreamerContext().getPoToken().toByteArray());
        assertArrayEquals(Base64.decode(USTREAMER_CONFIG, Base64.URL_SAFE), request.getVideoPlaybackUstreamerConfig().toByteArray());
        assertEquals("WEB", request.getStreamerContext().getClientInfo().getClientName().name());
    }

    /**
     * A live stream's formats have no lastModified: -1 named a format the server doesn't have, and it answered with a reload of the
     * player response (2026-11-15)
     */
    @Test
    public void anUnknownLastModifiedIsntSent() throws Exception {
        VideoPlaybackAbrRequest request = VideoPlaybackAbrRequest.parseFrom(SabrLiveHeadProtocol.createRequestBody(
                USTREAMER_CONFIG, null, clientInfo(), new SabrLiveHead.Format(140, -1, null, 144_000),
                new SabrLiveHead.Format(278, 1_766_955_883_819_090L, "CgcKAnZiEgEw", 95_000)));

        assertFalse(request.getPreferredAudioFormatIds(0).hasLastModified());
        assertEquals(1_766_955_883_819_090L, request.getPreferredVideoFormatIds(0).getLastModified());
        assertEquals("CgcKAnZiEgEw", request.getPreferredVideoFormatIds(0).getXtags());
        assertFalse(request.getStreamerContext().hasPoToken());
    }

    /**
     * As the server sends it: the protection status and request parts first (their sizes in varints of each length), then the live
     * metadata, then the media, which isn't read
     */
    @Test
    public void readsTheHeadFromTheLiveMetadata() throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        part(response, UMPPartId.STREAM_PROTECTION_STATUS, new byte[3]);
        part(response, UMPPartId.PLAYBACK_START_POLICY, new byte[300]); // a 2-byte size
        part(response, UMPPartId.REQUEST_IDENTIFIER, new byte[20_000]); // a 3-byte size
        // Sizes in 4 and 5 bytes, as the decoder takes them for any value (a part that needs them is megabytes)
        response.write(UMPPartId.REQUEST_CANCELLATION_POLICY);
        response.write(new byte[] {(byte) (0xE0 | 5), 0, 0, 0});
        response.write(new byte[5]);
        response.write(UMPPartId.SELECTABLE_FORMATS);
        response.write(new byte[] {(byte) 0xF0, 7, 0, 0, 0});
        response.write(new byte[7]);
        part(response, UMPPartId.LIVE_METADATA, LiveMetadata.newBuilder()
                .setHeadSequenceNumber(HEAD_SEQUENCE)
                .setHeadSequenceTimeMs(HEAD_TIME_MS)
                .setWallTimeMs(WALL_TIME_MS)
                .build().toByteArray());
        response.write(UMPPartId.MEDIA_HEADER); // cut short: never read
        ByteArrayInputStream stream = new ByteArrayInputStream(response.toByteArray());

        SabrLiveHeadProtocol.Result result = SabrLiveHeadProtocol.readResponse(stream);

        assertEquals(HEAD_SEQUENCE, result.head.headSequenceNumber);
        assertEquals(HEAD_TIME_MS, result.head.headTimeMs);
        assertEquals(WALL_TIME_MS, result.head.wallTimeMs);
        assertNull(result.redirectUrl);
        assertEquals(1, stream.available());
    }

    @Test
    public void aRedirectIsFollowed() throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        part(response, UMPPartId.SABR_REDIRECT, SabrRedirect.newBuilder()
                .setRedirectUrl("https://rr2---sn-other.googlevideo.com/videoplayback").build().toByteArray());

        SabrLiveHeadProtocol.Result result = SabrLiveHeadProtocol.readResponse(new ByteArrayInputStream(response.toByteArray()));

        assertNull(result.head);
        assertEquals("https://rr2---sn-other.googlevideo.com/videoplayback", result.redirectUrl);
    }

    @Test
    public void noHeadWithoutLiveMetadataBeforeTheMedia() throws Exception {
        for (int partId : new int[] {UMPPartId.RELOAD_PLAYER_RESPONSE, UMPPartId.SABR_ERROR, UMPPartId.MEDIA_HEADER}) {
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            part(response, partId, new byte[4]);
            part(response, UMPPartId.LIVE_METADATA, LiveMetadata.newBuilder().setHeadSequenceNumber(1).build().toByteArray());

            SabrLiveHeadProtocol.Result result = SabrLiveHeadProtocol.readResponse(new ByteArrayInputStream(response.toByteArray()));

            assertNull("part " + partId, result.head);
            assertNull("part " + partId, result.redirectUrl);
        }

        assertNull(SabrLiveHeadProtocol.readResponse(new ByteArrayInputStream(new byte[0])).head);
    }

    @Test(expected = EOFException.class)
    public void aPartCutShortFails() throws Exception {
        ByteArrayOutputStream response = new ByteArrayOutputStream();
        response.write(UMPPartId.LIVE_METADATA);
        response.write(10);
        response.write(new byte[4]);

        SabrLiveHeadProtocol.readResponse(new ByteArrayInputStream(response.toByteArray()));
    }

    private static MediaItemFormatInfo.ClientInfo clientInfo() {
        MediaItemFormatInfo.ClientInfo clientInfo = mock(MediaItemFormatInfo.ClientInfo.class);
        when(clientInfo.getClientName()).thenReturn("WEB");
        when(clientInfo.getClientVersion()).thenReturn("test");
        when(clientInfo.getOsName()).thenReturn("");
        when(clientInfo.getOsVersion()).thenReturn("");
        return clientInfo;
    }

    /**
     * Its id and size as UMP varints (see UMPDecoder#readVarInt)
     */
    private static void part(ByteArrayOutputStream out, int partId, byte[] payload) throws Exception {
        writeVarInt(out, partId);
        writeVarInt(out, payload.length);
        out.write(payload);
    }

    private static void writeVarInt(ByteArrayOutputStream out, long value) {
        if (value < 0x80) {
            out.write((int) value);
        } else if (value < 1 << 14) {
            out.write(0x80 | (int) (value & 0x3F));
            out.write((int) (value >> 6));
        } else if (value < 1 << 21) { // 3 bytes
            out.write(0xC0 | (int) (value & 0x1F));
            out.write((int) (value >> 5) & 0xFF);
            out.write((int) (value >> 13));
        } else {
            throw new IllegalArgumentException("A test part of " + value + " bytes");
        }
    }
}
