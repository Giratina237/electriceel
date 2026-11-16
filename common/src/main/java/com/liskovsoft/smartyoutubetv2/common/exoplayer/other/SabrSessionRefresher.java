package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.sabr.manifest.SabrManifest;
import com.google.android.exoplayer2.source.sabr.manifest.SabrManifestParser;
import com.google.android.exoplayer2.source.sabr.manifest.SabrSession;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

/**
 * Fetches the formats of a SABR stream's video again when its server asks for the player response again (see
 * SabrManifest#requestSessionRefresh), e.g. as its session expires about 6 h into a video. Their session is swapped in while the
 * player plays what it buffered. Before, the app reloaded the video: the playback stopped for about 3 s, and the audio output was
 * closed and opened again (SmartTube #6478).
 */
public final class SabrSessionRefresher implements SabrManifest.SessionRefresher {
    private static final String TAG = SabrSessionRefresher.class.getSimpleName();
    private final String mVideoId;
    @Nullable
    private final Listener mListener;

    /**
     * Told the formats a new session came with, once it's swapped in: on the main thread
     */
    public interface Listener {
        void onStreamsRefreshed(MediaItemFormatInfo formatInfo);
    }

    public SabrSessionRefresher(String videoId, @Nullable Listener listener) {
        mVideoId = videoId;
        mListener = listener;
    }

    /**
     * On the playback thread: the fetch starts on the main one, as the app's other fetches of the formats do
     */
    @Override
    public void refresh(@Nullable String reloadPlaybackToken, SabrManifest.SessionCallback callback) {
        Utils.post(() -> fetchFormatInfo(reloadPlaybackToken, callback));
    }

    private void fetchFormatInfo(@Nullable String reloadPlaybackToken, SabrManifest.SessionCallback callback) {
        Log.d(TAG, "Fetching the formats of %s again for a new SABR session", mVideoId);
        // The cached formats are dropped, and the next player request of the video has the server's token (see ReloadPlaybackGate):
        // without it, the server keeps asking for it
        YouTubeServiceManager.instance().reloadPlayerResponse(mVideoId, reloadPlaybackToken);
        RxHelper.execute(MediaServiceManager.getFormatInfoObserve(mVideoId),
                formatInfo -> onFormatInfo(formatInfo, callback),
                error -> {
                    Log.e(TAG, "The formats of %s failed: %s", mVideoId, error.getMessage());
                    callback.onSession(null);
                });
    }

    private void onFormatInfo(MediaItemFormatInfo formatInfo, SabrManifest.SessionCallback callback) {
        SabrSession session = !formatInfo.isUnplayable() && formatInfo.containsSabrFormats()
                ? SabrManifestParser.parseSession(formatInfo) : null;

        if (callback.onSession(session) && mListener != null) {
            mListener.onStreamsRefreshed(formatInfo);
        }
    }
}
