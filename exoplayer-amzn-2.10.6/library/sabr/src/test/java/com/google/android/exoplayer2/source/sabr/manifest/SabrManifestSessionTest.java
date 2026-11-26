package com.google.android.exoplayer2.source.sabr.manifest;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.source.sabr.protos.videostreaming.VideoPlaybackAbrRequest;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/**
 * A refused request says how its session came (see SabrManifest#describeRequest): a refusal 38-95 ms into a new session (0CW972QK,
 * H8ZP1GPV) didn't come again with sessions the app asked for, and the server's asks, with their reload token, were the difference left
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 28)
public class SabrManifestSessionTest {
    @Test
    public void eachSessionSaysHowItCame() {
        SabrManifest manifest = new SabrManifestParser().parse(formatInfo());
        manifest.setSessionRefresher((token, callback) -> callback.onSession(new SabrSession("https://media.example/videoplayback?n=2",
                "Cg==", null, manifest.getClientInfo(), Collections.emptySet())));

        assertEquals(SabrManifest.SESSION_FIRST, manifest.getSessionOrigin());

        manifest.requestSessionRefresh(manifest.getSessionId(), "reload-token");

        assertEquals(1, manifest.getSessionId());
        assertEquals(SabrManifest.SESSION_ASKED_WITH_TOKEN, manifest.getSessionOrigin());

        // Fetched again before they expire (see SabrManifestParser#refresh)
        manifest.refreshSession("https://media.example/videoplayback?n=3", "Cg==", null);

        assertEquals(SabrManifest.SESSION_REFETCHED, manifest.getSessionOrigin());
    }

    @Test
    public void theRequestSaysHowItsSessionCame() {
        assertEquals("init, video none, audio none, selected none, session 2 (server-asked)",
                SabrManifest.describeRequest(VideoPlaybackAbrRequest.newBuilder().build(), true, 2, SabrManifest.SESSION_ASKED));
    }

    private static MediaItemFormatInfo formatInfo() {
        MediaItemFormatInfo formatInfo = mock(MediaItemFormatInfo.class);
        MediaItemFormatInfo.ClientInfo clientInfo = mock(MediaItemFormatInfo.ClientInfo.class);
        when(clientInfo.getClientName()).thenReturn("TVHTML5");
        when(clientInfo.getClientVersion()).thenReturn("test");
        when(clientInfo.getOsName()).thenReturn("");
        when(clientInfo.getOsVersion()).thenReturn("");
        when(formatInfo.getClientInfo()).thenReturn(clientInfo);
        when(formatInfo.getLengthSeconds()).thenReturn("60");
        when(formatInfo.getVideoId()).thenReturn("Hf-CqpBeDs0");
        when(formatInfo.getServerAbrStreamingUrl()).thenReturn("https://media.example/videoplayback?n=1");
        when(formatInfo.getVideoPlaybackUstreamerConfig()).thenReturn("Cg==");
        when(formatInfo.getAdaptiveFormats()).thenReturn(Collections.emptyList());
        when(formatInfo.getSubtitles()).thenReturn(Collections.emptyList());

        return formatInfo;
    }
}
