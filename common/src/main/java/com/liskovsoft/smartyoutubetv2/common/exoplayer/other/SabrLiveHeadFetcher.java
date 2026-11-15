package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.sabr.SabrLiveHeadProtocol;
import com.google.android.exoplayer2.source.sabr.parser.misc.Utils;
import com.liskovsoft.googlecommon.common.helpers.DefaultHeaders;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.youtubeapi.videoinfo.SabrLiveHead;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * A live stream's head over SABR, for one whose formats have no URLs (see SabrLiveHead, SabrLiveHeadProtocol): on the app's OkHttp
 * client (its DNS and IPv6 settings), the connection closed once the live metadata is read
 */
public final class SabrLiveHeadFetcher implements SabrLiveHead.Source {
    private static final String TAG = SabrLiveHeadFetcher.class.getSimpleName();
    private static final MediaType PROTOBUF = MediaType.parse("application/x-protobuf");
    private static final int MAX_REDIRECTS = 2;
    private static final long TIMEOUT_MS = 5_000;

    public static void install() {
        SabrLiveHead.setSource(new SabrLiveHeadFetcher());
    }

    @Nullable
    @Override
    public SabrLiveHead fetch(String serverAbrStreamingUrl, String videoPlaybackUstreamerConfig, @Nullable String poToken,
                              MediaItemFormatInfo.ClientInfo clientInfo, SabrLiveHead.Format audio,
                              @Nullable SabrLiveHead.Format video) throws IOException {
        byte[] body = SabrLiveHeadProtocol.createRequestBody(videoPlaybackUstreamerConfig, poToken, clientInfo, audio, video);
        OkHttpClient client = OkHttpManager.instance().getClient().newBuilder()
                .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build();
        String url = serverAbrStreamingUrl;

        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            Request request = new Request.Builder()
                    .url(Utils.updateQuery(url, "rn", 0))
                    .header("User-Agent", DefaultHeaders.APP_USER_AGENT)
                    .header("Accept", "application/vnd.yt-ump")
                    .post(RequestBody.create(body, PROTOBUF))
                    .build();

            try (Response response = client.newCall(request).execute()) {
                ResponseBody responseBody = response.body();

                if (!response.isSuccessful() || responseBody == null) {
                    Log.e(TAG, "Response %s", response.code());
                    return null;
                }

                SabrLiveHeadProtocol.Result result = SabrLiveHeadProtocol.readResponse(responseBody.byteStream());

                if (result.head != null || result.redirectUrl == null) {
                    return result.head;
                }

                url = result.redirectUrl;
            }
        }

        return null;
    }
}
