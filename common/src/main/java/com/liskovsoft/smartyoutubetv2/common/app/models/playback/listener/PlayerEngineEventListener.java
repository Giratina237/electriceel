package com.liskovsoft.smartyoutubetv2.common.app.models.playback.listener;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.PlayerFailure;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;

public interface PlayerEngineEventListener {
    /**
     * The error occurred loading data
     */
    int ERROR_TYPE_SOURCE = ExoPlaybackException.TYPE_SOURCE;
    /**
     * The error occurred in a renderer.
     */
    int ERROR_TYPE_RENDERER = ExoPlaybackException.TYPE_RENDERER;
    /**
     * The error was an unexpected
     */
    int ERROR_TYPE_UNEXPECTED = ExoPlaybackException.TYPE_UNEXPECTED;
    /**
     * The error occurred in a remote component.
     */
    int ERROR_TYPE_REMOTE = ExoPlaybackException.TYPE_REMOTE;
    /**
     * The error was an {@link OutOfMemoryError}.
     */
    int ERROR_TYPE_OUT_OF_MEMORY = ExoPlaybackException.TYPE_OUT_OF_MEMORY;
    int RENDERER_INDEX_UNKNOWN = TrackSelectorManager.RENDERER_INDEX_UNKNOWN;
    int RENDERER_INDEX_VIDEO = TrackSelectorManager.RENDERER_INDEX_VIDEO;
    int RENDERER_INDEX_AUDIO = TrackSelectorManager.RENDERER_INDEX_AUDIO;
    int RENDERER_INDEX_SUBTITLE = TrackSelectorManager.RENDERER_INDEX_SUBTITLE;
    void onPlay();
    void onPause();
    void onPlayEnd();
    void onBuffering();
    void onSeekEnd();
    void onSeekPositionChanged(long positionMs);
    void onSpeedChanged(float speed);
    void onSourceChanged(Video item);
    void onVideoLoaded(Video item);
    void onEngineInitialized();
    void onEngineReleased();
    void onEngineError(int type, int rendererIndex, Throwable error);
    /**
     * The video's formats were fetched again and their streams swapped in while it played (see SabrSessionRefresher)
     */
    void onStreamsRefreshed(MediaItemFormatInfo formatInfo);
    /**
     * The playback goes wrong without an error (see PlaybackHealth's kinds, e.g. stalls again and again): a fix adds its actions to
     * the failure, recorded after (see ErrorFixerController#onHealthFailure). Nothing by default.
     */
    default void onHealthFailure(String kind, PlayerFailure failure) {
        // NOP
    }
    /**
     * A live stream's segments answer 404 on both tracks, and none loaded since, for a while (see LiveSegmentsGone): a fix adds its
     * actions to the 404's failure, recorded after (see ErrorFixerController#onLiveSegmentsGone). Nothing by default.
     */
    default void onLiveSegmentsGone(PlayerFailure failure) {
        // NOP
    }
    void onTrackChanged(FormatItem track);
    void onTrackSelected(FormatItem track);
}
