package com.google.android.exoplayer2.source.sabr;

import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.sabr.manifest.SabrManifestParser;
import com.google.android.exoplayer2.source.sabr.parser.misc.EnabledTrackTypes;
import com.google.android.exoplayer2.source.sabr.parser.ump.UMPPartId;
import com.google.android.exoplayer2.source.sabr.protos.misc.FormatId;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.ClientAbrState;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.LiveMetadata;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.SabrRedirect;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.StreamerContext;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.google.protobuf.ByteString;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.youtubeapi.videoinfo.SabrLiveHead;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * The SABR side of a live stream's head (see SabrLiveHead): the player's first request (its init chunk's, see
 * SabrManifest#createVideoPlaybackAbrRequest), audio only, and the live metadata its response starts with, before any media. The
 * HTTP is the app's.
 */
public final class SabrLiveHeadProtocol {
    private static final String TAG = SabrLiveHeadProtocol.class.getSimpleName();
    /**
     * Of a response read for its live metadata, which comes before the media (seen in each response, 2026-11-15)
     */
    private static final int MAX_READ_BYTES = 256 * 1024;

    /**
     * What a response said: the head, or the URL the server sends the stream to, or neither (an error, the media came first)
     */
    public static final class Result {
        @Nullable public final SabrLiveHead head;
        @Nullable public final String redirectUrl;

        private Result(@Nullable SabrLiveHead head, @Nullable String redirectUrl) {
            this.head = head;
            this.redirectUrl = redirectUrl;
        }
    }

    private SabrLiveHeadProtocol() {
    }

    /**
     * As the player's first request for its audio track (see SabrManifest#createVideoPlaybackAbrRequest): both tracks' formats named,
     * audio enabled
     */
    public static byte[] createRequestBody(String videoPlaybackUstreamerConfig, @Nullable String poToken,
                                           MediaItemFormatInfo.ClientInfo clientInfo, SabrLiveHead.Format audio,
                                           @Nullable SabrLiveHead.Format video) {
        StreamerContext.Builder streamerContext = StreamerContext.newBuilder()
                .setClientInfo(SabrManifestParser.createClientInfo(clientInfo));

        if (poToken != null && !poToken.isEmpty()) {
            streamerContext.setPoToken(ByteString.copyFrom(Base64.decode(poToken, Base64.URL_SAFE)));
        }

        VideoPlaybackAbrRequest.Builder request = VideoPlaybackAbrRequest.newBuilder()
                .setClientAbrState(ClientAbrState.newBuilder()
                        .setSabrForceMaxNetworkInterruptionDurationMs(0)
                        .setPlaybackRate(1)
                        .setPlayerTimeMs(0)
                        .setClientViewportIsFlexible(false)
                        .setBandwidthEstimate(audio.bitrate)
                        .setDrcEnabled(false)
                        .setEnabledTrackTypesBitfield(EnabledTrackTypes.AUDIO_ONLY)
                        .build())
                .addPreferredAudioFormatIds(toFormatId(audio))
                .setVideoPlaybackUstreamerConfig(ByteString.copyFrom(Base64.decode(videoPlaybackUstreamerConfig, Base64.URL_SAFE)))
                .setStreamerContext(streamerContext.build());

        if (video != null) {
            request.addPreferredVideoFormatIds(toFormatId(video));
        }

        return request.build().toByteArray();
    }

    private static FormatId toFormatId(SabrLiveHead.Format format) {
        FormatId.Builder formatId = FormatId.newBuilder()
                .setItag(format.itag);

        // A live stream's formats have none: -1 named a format the server doesn't have, and it answered with a reload
        if (format.lastModified > 0) {
            formatId.setLastModified(format.lastModified);
        }

        if (format.xtags != null && !format.xtags.isEmpty()) {
            formatId.setXtags(format.xtags);
        }

        return formatId.build();
    }

    /**
     * Reads the response up to its live metadata (the rest is left unread, to be closed)
     */
    public static Result readResponse(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        long read = 0;

        while (read < MAX_READ_BYTES) {
            int partId = (int) readVarInt(input);

            if (partId == -1) {
                return new Result(null, null); // the response ended
            }

            int size = (int) readVarInt(input);

            if (size < 0) {
                throw new EOFException("UMP part " + partId + " without a size");
            }

            switch (partId) {
                case UMPPartId.LIVE_METADATA:
                    LiveMetadata metadata = LiveMetadata.parseFrom(readPart(input, size));
                    return new Result(new SabrLiveHead(metadata.getHeadSequenceNumber(), metadata.getHeadSequenceTimeMs(),
                            metadata.getWallTimeMs()), null);
                case UMPPartId.SABR_REDIRECT:
                    SabrRedirect redirect = SabrRedirect.parseFrom(readPart(input, size));
                    return new Result(null, redirect.hasRedirectUrl() ? redirect.getRedirectUrl() : null);
                case UMPPartId.SABR_ERROR:
                case UMPPartId.RELOAD_PLAYER_RESPONSE:
                case UMPPartId.MEDIA_HEADER:
                case UMPPartId.MEDIA:
                    Log.e(TAG, "UMP part %s before the live metadata", partId);
                    return new Result(null, null);
                default:
                    skipFully(input, size);
            }

            read += size;
        }

        return new Result(null, null);
    }

    private static byte[] readPart(DataInputStream input, int size) throws IOException {
        byte[] data = new byte[size];
        input.readFully(data);
        return data;
    }

    private static void skipFully(DataInputStream input, int size) throws IOException {
        int skipped = 0;

        while (skipped < size) {
            int count = input.skipBytes(size - skipped);

            if (count <= 0) {
                if (input.read() == -1) {
                    throw new EOFException("UMP part cut short");
                }
                count = 1;
            }

            skipped += count;
        }
    }

    /**
     * UMP's (see UMPDecoder#readVarInt), -1 at the end of the stream
     */
    private static long readVarInt(DataInputStream input) throws IOException {
        int first = input.read();

        if (first == -1) {
            return -1;
        }

        int size = first < 128 ? 1 : first < 192 ? 2 : first < 224 ? 3 : first < 240 ? 4 : 5;
        long result = 0;
        int shift = 0;

        if (size != 5) {
            shift = 8 - size;
            result = first & ((1 << shift) - 1);
        }

        for (int i = 1; i < size; i++) {
            int next = input.read();

            if (next == -1) {
                throw new EOFException("UMP varint cut short");
            }

            result |= (long) next << shift;
            shift += 8;
        }

        return result;
    }
}
