package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.Build.VERSION;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.android.exoplayer2.source.sabr.ValidatedNetwork;
import com.liskovsoft.mediaserviceinterfaces.data.MediaFormat;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata;
import com.liskovsoft.sharedutils.helpers.DateHelper;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.FormatAttempts;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.PlayerFailure;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.VideoStart;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.VideoStarts;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MusicAutoplayManager;
import com.liskovsoft.smartyoutubetv2.common.misc.VideoCategoryManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.videoinfo.V2.ClientAttempts;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

public class VideoLoaderController extends BasePlayerController {
    private static final String TAG = VideoLoaderController.class.getSimpleName();
    private static final int MIN_SHUFFLE_SIZE = 30;
    /**
     * A live stream a client answered OK for with nothing to play it from: the clients tried for it, one after another, before it's
     * only reloaded (see processFormatInfo)
     */
    private static final int MAX_LIVE_CLIENT_SWITCHES = 3;
    private static final int EMPTY_FORMATS_RELOAD_MS = 30_000;
    /**
     * A premiere or a live stream that hasn't started: it has no formats yet, and that's no failure (see processFormatInfo)
     */
    private static final String STATUS_LIVE_STREAM_OFFLINE = "LIVE_STREAM_OFFLINE";
    /**
     * The longest wait between the reloads of one that starts later (see getEmptyFormatsReloadMs)
     */
    private static final int MAX_UPCOMING_RELOAD_MS = 10 * 60 * 1_000;
    /**
     * Where a stopped video is left, before its end: its length is whole seconds (lengthSeconds), up to a second past its media
     * (see stopPlayback)
     */
    static final long STOP_BEFORE_END_MS = 1_000;
    /**
     * Of the video's media left to play, when the formats of the one its end opens are fetched (see prefetchNextIfNeeded)
     */
    @VisibleForTesting
    static final long PREFETCH_BEFORE_END_MS = 20_000;
    /**
     * Played again this close to when its streams expire (YouTube gives them about 6 h), the video's are fetched again first. Resumed
     * 7.6 min before, a video played 8 min and the server stopped it for a reload (FARAJV5R #100). See onPlay.
     */
    @VisibleForTesting
    static final long RESUME_REFRESH_BEFORE_EXPIRY_MS = 30 * 60_000;
    /**
     * While a SABR video plays, this long before its streams expire they're fetched again and played on with, without a reload (see
     * refreshStreamsWhilePlaying). The cached formats are fetched again from then too (see MediaItemFormatInfo#STREAMS_EXPIRE_MARGIN_MS).
     */
    @VisibleForTesting
    static final long STREAMS_REFRESH_BEFORE_EXPIRY_MS = MediaItemFormatInfo.STREAMS_EXPIRE_MARGIN_MS;
    /**
     * With less left, the streams fetched again might come after the ones in use expired: the video is reloaded instead (see onPlay)
     */
    @VisibleForTesting
    static final long MIN_REFRESH_WHILE_PLAYING_MS = 60_000;
    /**
     * What the end of a video does, of the videos (see getEndStep): opens the next one, the previous one, or stays with it (repeats it,
     * stops, closes…)
     */
    private static final int END_NEXT = 0;
    private static final int END_PREVIOUS = 1;
    private static final int END_STAY = 2;
    private final Playlist mPlaylist;
    private Video mPendingVideo;
    private SuggestionsController mSuggestionsController;
    private ErrorFixerController mErrorFixerController;
    private CensoredWordsController mCensoredWordsController;
    private EndScreenController mEndScreenController;
    private Disposable mFormatInfoAction;
    /**
     * The streams of the SABR video playing, fetched again to play on with (see refreshStreams)
     */
    private Disposable mRefreshStreamsAction;
    /**
     * Fetched while the engine starts (see prefetchFormatInfo), for the video in mPrefetchVideoId
     */
    private Observable<MediaItemFormatInfo> mPrefetchedFormatInfo;
    private String mPrefetchVideoId;
    /**
     * The fetch of the formats of the video the end opens, ahead of it (see prefetchNext): one at a time, never disposed (that would
     * cancel its request, and its client would be thought to fail). Its video, once per load of the video playing.
     */
    private Disposable mNextPrefetchAction;
    private String mNextPrefetchVideoId;
    private Disposable mMusicAutoplayAction;
    /**
     * Where the formats in use are from and how they're played (see PlayerFailure#source), of the video in mFormatVideoId
     */
    private String mFormatVideoId;
    private String mFormatClient;
    private String mFormatProtocol;
    /**
     * When the streams of the formats in use expire (see MediaItemFormatInfo#getStreamsExpireTimeMs), of the video in mFormatVideoId:
     * 0 when unknown, or once they're being fetched again for it
     */
    private long mStreamsExpireTimeMs;
    /**
     * How long the server gave them, from when they were made: 0 when unknown (see onPlay)
     */
    private long mStreamsLifetimeMs;
    /**
     * The live stream the clients are switched for, and how many times (see MAX_LIVE_CLIENT_SWITCHES)
     */
    private String mLiveSwitchVideoId;
    private int mLiveClientSwitches;
    /**
     * By video and channel: the switches a live stream took in this run (see applyKnownLiveSwitches)
     */
    private final Map<String, Integer> mLiveSwitches = new HashMap<>();
    /**
     * The client picked in the player for the video in mPickedClientVideoId, its formats asked of it first (see pickClient)
     */
    private String mPickedClient;
    private String mPickedClientVideoId;
    /**
     * The formats came from another client than the one picked, said once (see showPickedClientFallback)
     */
    private boolean mIsPickedClientFallbackShown;
    /**
     * The video openVideoInt started last and how (see VideoStart#TRIGGER_AUTOPLAY): a video only the channel's members watch is skipped
     * when it came next, not when the user opened it (see skipMembersVideo). Null once a video starts another way (see onNewVideo).
     */
    private Video mStartVideo;
    private String mStartTrigger;
    /**
     * The videos skipped in a row for only the channel's members watching them (see skipMembersVideo), till one plays
     */
    private int mMembersSkips;
    /**
     * Skipped before its metadata loaded, which says what's next: the next one plays once it has (see skipMembersVideo)
     */
    private String mMembersSkipVideoId;
    /**
     * In a row, at most: each one skipped asks for its formats first
     */
    private static final int MAX_MEMBERS_SKIPS = 5;
    /**
     * When the reload and the engine restart asked for run (elapsed realtime), -1 for none asked for (see isFixPending)
     */
    private long mReloadDueMs = -1;
    private long mRestartDueMs = -1;
    /**
     * Why the reload and the engine restart asked for last are done, for their start (see VideoStart#reason): the kind of failure the
     * error fixer acted on, or a VideoStart.REASON_ constant. Null when not told.
     */
    @Nullable
    private String mReloadReason;
    @Nullable
    private String mRestartReason;
    /**
     * The reason told for the reload or restart being asked for (see #reloadVideo(int, String)), taken by the one asked for: one
     * asked for without (see #reloadVideo(int)) has none
     */
    @Nullable
    private String mNextReloadReason;
    @Nullable
    private String mNextRestartReason;
    private final Runnable mReloadVideo = () -> {
        mReloadDueMs = -1;
        Video video = getVideo();

        if (video != null) {
            video.fromReload = true;
            VideoStarts.onReload(video.videoId, mReloadReason);
        }

        getMainController().onNewVideo(video);
    };
    private final Runnable mLoadNext = () -> loadNext(VideoStart.TRIGGER_AUTOPLAY);
    private final Runnable mMetadataSync = () -> {
        if (getPlayer() != null) {
            waitMetadataSync(getVideo(), false, VideoStart.TRIGGER_AUTOPLAY);
        }
    };
    private final Runnable mRestartEngine = () -> {
        mRestartDueMs = -1;

        if (getPlayer() != null) {
            Video video = getVideo();

            if (video != null) {
                VideoStarts.onReload(video.videoId, mRestartReason);
            }

            getPlayer().restartEngine(); // properly save position of the current track
        }
    };
    private final Runnable mOnApplyPlaybackMode = () -> {
        if (getPlayer() != null && getPlayer().getPositionMs() >= getPlayer().getDurationMs()) {
            applyPlaybackMode(getPlaybackMode());
        }
    };
    private final Runnable mShowProgressBar = () -> {
        if (getPlayer() != null) {
            getPlayer().showProgressBar(true);
        }
    };
    private final Runnable mPrefetchNext = this::prefetchNextIfNeeded;
    private final Runnable mRefreshStreams = this::refreshStreamsWhilePlaying;

    public VideoLoaderController() {
        mPlaylist = Playlist.instance();
    }

    @Override
    public void onInit() {
        mSuggestionsController = getController(SuggestionsController.class);
        mErrorFixerController = getController(ErrorFixerController.class);
        mCensoredWordsController = getController(CensoredWordsController.class);
        mEndScreenController = getController(EndScreenController.class);
    }

    @Override
    public void onNewVideo(Video item) {
        if (item == null) {
            return;
        }

        // Not started by openVideoInt (a card, a link, the queue): it didn't come next. A reload is of the same video.
        if (item != mStartVideo) {
            mStartVideo = null;
            mStartTrigger = null;
        }

        item.isShuffled = false;

        if (!item.fromQueue && !item.belongsToPlaybackQueue()) {
            mPlaylist.add(item);
        } else {
            item.fromQueue = false;
        }

        if (getPlayer() != null && getPlayer().isEngineInitialized()) { // player is initialized
            // Fix improperly resized video after exit from PIP (Device Formuler Z8 Pro)
            loadVideo(item); // force play immediately even the same video
        } else {
            VideoStarts.onEngineWait(item.videoId);
            mPendingVideo = item;
            prefetchFormatInfo(item);
        }
    }

    @Override
    public void onEngineInitialized() {
        if (getPlayer() == null) {
            return;
        }

        VideoStarts.onEngineInitialized();
        loadVideo(Helpers.firstNonNull(mPendingVideo, getVideo()));
        updateRepeatButton();
        mPendingVideo = null;
    }

    @Override
    public void onEngineReleased() {
        disposeActions();
    }

    @Override
    public void onVideoLoaded(Video video) {
        if (getPlayer() == null) {
            return;
        }
        
        updateRepeatButton();
        resolveMusicCategory(video);
        // Can't set title at this point
        //checkSleepTimer();
    }

    @Override
    public boolean onPreviousClicked() {
        loadPrevious();

        return true;
    }

    @Override
    public boolean onNextClicked() {
        if (getGeneralData().isChildModeEnabled()) {
            onPlayEnd();
        } else {
            loadNext();
        }

        return true;
    }

    public void loadPrevious() {
        if (getPlayer() == null) {
            return;
        }

        openVideoInt(mSuggestionsController.getPrevious(), VideoStart.TRIGGER_PREVIOUS);

        if (getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
            getPlayer().showOverlay(true);
        }
    }

    public void loadNext() {
        loadNext(VideoStart.TRIGGER_NEXT);
    }

    /**
     * @param trigger of the start of the next video, see VideoStart#TRIGGER_NEXT
     */
    private void loadNext(String trigger) {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }

        Video next = mSuggestionsController.getNext();

        if (next != null) {
            openVideoInt(next, trigger);
        } else if (mSuggestionsController.isShortsGroupPlaying()) {
            // The last of its group's cards: the group's next page has the next Short, never YouTube's next video (SmartTube #6490)
            mSuggestionsController.loadNextShort(() -> loadNext(trigger));
        } else {
            waitMetadataSync(getVideo(), true, trigger);
        }

        if (getPlayerTweaksData().isPlayerUiOnNextEnabled()) {
            getPlayer().showOverlay(true);
        }
    }

    /**
     * Played again, e.g. after a long pause: when its streams expire before long, the server would refuse the next segment (SABR
     * asks for the player response again, a URL answers 403). Fetched again: a SABR video's while it plays on, when they haven't
     * expired yet; else first, reloaded from where it is.
     */
    @Override
    public void onPlay() {
        Video video = getVideo();

        // Streams given for no longer than that: the ones fetched again would be as close to expiring, fetched again at their start, and
        // so on. Only their last minutes then, as before.
        long marginMs = mStreamsLifetimeMs > RESUME_REFRESH_BEFORE_EXPIRY_MS
                ? RESUME_REFRESH_BEFORE_EXPIRY_MS : MediaItemFormatInfo.STREAMS_EXPIRE_MARGIN_MS;

        if (getPlayer() == null || video == null || video.isLive || !isFormatOfVideo() || mStreamsExpireTimeMs <= 0
                || System.currentTimeMillis() < mStreamsExpireTimeMs - marginMs) {
            // Not with a reload: its fetch would wait for the prefetch
            prefetchNextIfNeeded();
            return;
        }

        long leftMs = mStreamsExpireTimeMs - System.currentTimeMillis();
        // Once for these formats: the ones fetched have their own time
        mStreamsExpireTimeMs = 0;

        if (PlayerFailure.PROTOCOL_SABR.equals(mFormatProtocol) && leftMs >= MIN_REFRESH_WHILE_PLAYING_MS) {
            Log.d(TAG, "The streams expire in " + leftMs / 1_000 + " s: fetching them again while it plays on");
            refreshStreams(video, true);
            return;
        }

        Log.d(TAG, "The streams expire: fetching them again before playing on");
        YouTubeServiceManager.instance().reloadPlayerResponse(video.videoId, null);
        reloadVideo(0, VideoStart.REASON_STREAMS_EXPIRED);
    }

    /**
     * Its formats were fetched again as the SABR server asked (see SabrSessionRefresher): the streams in use now expire when theirs do.
     * Played again after a pause, it isn't reloaded for the ones before, and they're fetched again before they expire.
     */
    @Override
    public void onStreamsRefreshed(MediaItemFormatInfo formatInfo) {
        if (isFormatOfVideo() && Helpers.equals(formatInfo.getVideoId(), mFormatVideoId)) {
            setStreamsExpireTime(formatInfo);
            scheduleStreamsRefresh();
        }
    }

    /**
     * Before the streams of the SABR video playing expire, they're fetched again and played on with: at their expiry the server
     * would stop it for a reload (6 h after they were fetched, FARAJV5R #100). Not while it's paused: played again, onPlay sees to them.
     */
    private void refreshStreamsWhilePlaying() {
        Video video = getVideo();
        PlaybackView player = getPlayer();

        if (player == null || video == null || !isStreamsToRefresh() || !player.isPlaying()) {
            return;
        }

        Log.d(TAG, "The streams expire soon: fetching them again while it plays");
        refreshStreams(video, false);
    }

    /**
     * Of the formats that reached the player: a SABR video's streams are fetched again before they expire (see
     * refreshStreamsWhilePlaying)
     */
    private void scheduleStreamsRefresh() {
        Utils.removeCallbacks(mRefreshStreams);

        if (!isStreamsToRefresh()) {
            return;
        }

        long delayMs = mStreamsExpireTimeMs - STREAMS_REFRESH_BEFORE_EXPIRY_MS - System.currentTimeMillis();

        // As close to expiring when they came (e.g. streams given for less than that): the ones fetched again would be too, fetched
        // again at once, and so on. Played again, onPlay sees to them.
        if (delayMs <= 0) {
            return;
        }

        Utils.postDelayed(mRefreshStreams, delayMs);
    }

    /**
     * A SABR video's whose streams expire at a known time: a live stream's are fetched again by the player
     */
    private boolean isStreamsToRefresh() {
        Video video = getVideo();

        return video != null && !video.isLive && isFormatOfVideo() && PlayerFailure.PROTOCOL_SABR.equals(mFormatProtocol)
                && mStreamsExpireTimeMs > 0;
    }

    /**
     * The video's streams are fetched again, the cached ones being those in use, and the player plays on with them (see
     * PlayerEngine#refreshSabr)
     *
     * @param reloadOnFailure when they can't be played on with: reloaded (e.g. on a resume, where the video waits anyway), or left to
     *                        the server's ask for a reload when they expire (see ErrorFixerController#applySabrReloadPlayerAction)
     */
    private void refreshStreams(Video video, boolean reloadOnFailure) {
        Utils.removeCallbacks(mRefreshStreams);
        RxHelper.disposeActions(mRefreshStreamsAction);
        String videoId = video.videoId;
        String client = mFormatClient;
        YouTubeServiceManager.instance().reloadPlayerResponse(videoId, null);
        // The cache is cleared: the next video's formats fetched ahead too
        mNextPrefetchVideoId = null;

        mRefreshStreamsAction = fetchStreams(videoId)
                .subscribe(formatInfo -> onStreamsRefreshed(videoId, client, formatInfo, reloadOnFailure),
                           error -> {
                               Log.e(TAG, "The streams weren't fetched again: " + error.getMessage());
                               onStreamsRefreshed(videoId, client, null, reloadOnFailure);
                           });
    }

    private void setStreamsExpireTime(MediaItemFormatInfo formatInfo) {
        mStreamsExpireTimeMs = formatInfo.getStreamsExpireTimeMs();
        mStreamsLifetimeMs = mStreamsExpireTimeMs > 0 && formatInfo.getCreatedTimeMs() > 0
                ? mStreamsExpireTimeMs - formatInfo.getCreatedTimeMs() : 0;
    }

    @VisibleForTesting
    Observable<MediaItemFormatInfo> fetchStreams(String videoId) {
        return getFormatInfoObserve(videoId);
    }

    /**
     * @param formatInfo null when they weren't fetched
     */
    private void onStreamsRefreshed(String videoId, @Nullable String client, @Nullable MediaItemFormatInfo formatInfo,
                                    boolean reloadOnFailure) {
        PlaybackView player = getPlayer();

        // Another video, or the formats again (e.g. a reload), came meanwhile
        if (player == null || !isFormatOfVideo() || !videoId.equals(mFormatVideoId) || !PlayerFailure.PROTOCOL_SABR.equals(mFormatProtocol)
                || !Helpers.equals(client, mFormatClient)) {
            return;
        }

        String newClient = formatInfo != null ? getClientName(formatInfo) : null;

        if (formatInfo != null && Helpers.equals(client, newClient) && player.refreshSabr(formatInfo)) {
            Log.d(TAG, "Playing on with the streams fetched again");
            setStreamsExpireTime(formatInfo);
            scheduleStreamsRefresh();
            return;
        }

        if (formatInfo != null) {
            Log.w(TAG, "The streams fetched again don't fit the ones in use: " + client + " → " + newClient);
        }

        if (reloadOnFailure) {
            reloadVideo(0, VideoStart.REASON_STREAMS_REFRESH_FAILED);
        }
    }

    @Override
    public void onSeekEnd() {
        prefetchNextIfNeeded();
    }

    @Override
    public void onSpeedChanged(float speed) {
        prefetchNextIfNeeded();
    }

    @Override
    public void onPlayEnd() {
        if (getPlayer() == null) {
            return;
        }

        // Stop the playback if the user is browsing options or reading comments
        int playbackMode = getPlaybackMode();
        if (getAppDialogPresenter().isDialogShown() && !getAppDialogPresenter().isOverlay() && playbackMode != PlayerConstants.PLAYBACK_MODE_ONE) {
            getAppDialogPresenter().setOnFinish(mOnApplyPlaybackMode);
        } else {
            applyPlaybackMode(playbackMode);
        }
    }

    @Override
    public void onSuggestionItemClicked(Video item) {
        openVideoInt(item, VideoStart.TRIGGER_SUGGESTION);

        if (getPlayer() != null)
            getPlayer().showControls(false);
    }

    @Override
    public boolean onKeyDown(int keyCode) {
        Utils.removeCallbacks(mRestartEngine);
        mRestartDueMs = -1;

        return false;
    }

    /**
     * Force load and play!
     */
    private void loadVideo(Video item) {
        if (getPlayer() != null && item != null) {
            mPlaylist.setCurrent(item);
            getPlayer().setVideo(item);
            getPlayer().resetPlayerState();
            loadFormatInfo(item);
        }
    }

    /**
     * Force load suggestions.
     */
    private void loadSuggestions(Video item) {
        if (getPlayer() == null) {
            return;
        }

        if (item != null) {
            mPlaylist.setCurrent(item);
            getPlayer().setVideo(item);
            mSuggestionsController.loadSuggestions(item);
        }
    }

    private void waitMetadataSync(Video current, boolean showLoadingMsg, String trigger) {
        if (current == null) {
            return;
        }

        if (current.nextMediaItem != null) {
            // As getNext gives it: past a video only the channel's members watch, which the Members setting hides (see MembersVideos)
            openVideoInt(mSuggestionsController.getNext(), trigger);
        } else if (!current.isSynced) { // Maybe there's nothing left. E.g. when casting from phone
            // Wait in a loop while suggestions have been loaded...
            if (showLoadingMsg) {
                MessageHelpers.showMessage(getContext(), R.string.wait_data_loading);
            }
            // Short videos next fix (suggestions aren't loaded yet)
            boolean isEnded = getPlayer() != null && Math.abs(getPlayer().getDurationMs() - getPlayer().getPositionMs()) < 100;
            if (isEnded) {
                Utils.postDelayed(mMetadataSync, 1_000);
            }
        }
    }

    private void loadFormatInfo(Video video) {
        if (getPlayer() == null) {
            return;
        }

        resetPickedClient(video);
        resetLiveClientSwitch(video);
        // Fix no progress on next video (the engine may still buffering a bit)
        //getPlayer().showProgressBar(true);
        Utils.post(mShowProgressBar);
        disposeActions();
        // Its own next video, or the same one fetched again (e.g. a reload dropped it, see FormatInfoWrapper#invalidateCache)
        mNextPrefetchVideoId = null;

        if (mPrefetchedFormatInfo == null || !Helpers.equals(video.videoId, mPrefetchVideoId)) {
            VideoStarts.onFetch(video.videoId); // a prefetch was timed when it started
        }

        mFormatInfoAction = takeFormatInfoObserve(video)
                .subscribe(this::processFormatInfo,
                           error -> {
                               if (getPlayer() != null) {
                                   getPlayer().showProgressBar(false);
                               }
                               mErrorFixerController.runFormatErrorAction(error);
                           });
    }

    /**
     * The formats are fetched while the engine starts, rather than after: the load of the video gets them (see takeFormatInfoObserve)
     */
    private void prefetchFormatInfo(Video video) {
        if (video.videoId == null) {
            return;
        }

        resetPickedClient(video);
        resetLiveClientSwitch(video);

        // Its WebView runs on the main thread mostly: the player screen comes up first, the load of the video fetches them
        if (YouTubeServiceManager.instance().isPoTokenToMake()) {
            return;
        }

        VideoStarts.onFetch(video.videoId);
        // Cached: its fetch isn't disposed with the load, which waits for it rather than fetching again
        mPrefetchedFormatInfo = getFormatInfoObserve(video.videoId).cache();
        mPrefetchVideoId = video.videoId;
        RxHelper.execute(mPrefetchedFormatInfo, error -> {}); // the load handles the error
    }

    /**
     * The formats fetched while the engine started, once. A reload of the video fetches them again.
     */
    private Observable<MediaItemFormatInfo> takeFormatInfoObserve(Video video) {
        Observable<MediaItemFormatInfo> prefetched = Helpers.equals(video.videoId, mPrefetchVideoId) ? mPrefetchedFormatInfo : null;

        mPrefetchedFormatInfo = null;
        mPrefetchVideoId = null;

        // Delivered after the load returns, like a fetch: a prefetch that's done (e.g. its formats were cached) would otherwise reach
        // the player inside onEngineInitialized, before the other controllers have had theirs
        return prefetched != null ? prefetched.observeOn(AndroidSchedulers.mainThread()) : getFormatInfoObserve(video.videoId);
    }

    /**
     * The player waits for them: not queued behind the pool's other tasks (see MediaServiceManager#getFormatInfoObserveNow)
     */
    private static Observable<MediaItemFormatInfo> getFormatInfoObserve(String videoId) {
        return MediaServiceManager.getFormatInfoObserveNow(videoId);
    }

    private void processFormatInfo(MediaItemFormatInfo formatInfo) {
        PlaybackView player = getPlayer();

        if (player == null || getVideo() == null) {
            return;
        }

        String bgImageUrl = null;

        VideoStarts.onFormats(getVideo().videoId, getClientName(formatInfo), formatInfo.getCreatedTimeMs());
        getVideo().sync(formatInfo);

        mFormatVideoId = getVideo().videoId;
        mFormatClient = getClientName(formatInfo);
        mFormatProtocol = null;
        setStreamsExpireTime(formatInfo);
        showPickedClientFallback(formatInfo);
        // Fetched again from another client: the start goes on (see VideoStarts#onFetch)
        boolean isRefetched = false;

        Video video = getVideo();
        VideoStarts.timeOpenStep(VideoStarts.STAGE_CENSORED_WORDS, null, () -> mCensoredWordsController.onFormatInfo(video, formatInfo));
        VideoStarts.timeOpenStep(VideoStarts.STAGE_END_SCREEN, null, () -> mEndScreenController.onFormatInfo(video, formatInfo));

        // Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
        applyAspectRatio(formatInfo);

        if (formatInfo.getPaidContentText() != null && getSponsorBlockData().isPaidContentNotificationEnabled()) {
            MessageHelpers.showMessage(getContext(), formatInfo.getPaidContentText());
        }

        if (formatInfo.isUnplayable()) {
            PlayerFailure failure = newFailure(PlayerFailure.KIND_UNPLAYABLE, formatInfo);

            if (isEmbedPlayer()) {
                failure.action("finish_embed").record();
                player.finish();
                return;
            }

            failure.action("load_next_video").record();

            player.setTitle(formatInfo.getPlayabilityReason());
            player.showProgressBar(false);
            mSuggestionsController.loadSuggestions(getVideo());
            bgImageUrl = getVideo().getBackgroundUrl();

            // 18+ video or the video is hidden/removed
            player.showOverlay(true);
            loadNextVideo(5_000);

            //if (formatInfo.isUnknownError()) { // the bot error or the video not available
            //    scheduleRebootAppTimer(5_000);
            //} else { // 18+ video or the video is hidden/removed
            //    scheduleNextVideoTimer(5_000);
            //}
        } else if (acceptAdaptiveFormats(formatInfo) && formatInfo.containsDashFormats()) {
            Log.d(TAG, "Loading regular video in dash format...");

            if (getPlayerTweaksData().isHighBitrateFormatsEnabled() && formatInfo.hasExtendedHlsFormats()) {
                mFormatProtocol = PlayerFailure.PROTOCOL_DASH + "+" + PlayerFailure.PROTOCOL_HLS;
                player.openMerged(formatInfo, formatInfo.getHlsManifestUrl());
            } else {
                mFormatProtocol = PlayerFailure.PROTOCOL_DASH;
                player.openDash(formatInfo);
            }
        } else if (acceptAdaptiveFormats(formatInfo) && formatInfo.containsSabrFormats()) {
            Log.d(TAG, "Loading video in sabr format...");
            mFormatProtocol = PlayerFailure.PROTOCOL_SABR;
            player.openSabr(formatInfo);
        } else if (acceptDashLive(formatInfo)) {
            Log.d(TAG, "Loading live video (current or past live stream) in dash format...");
            mFormatProtocol = PlayerFailure.PROTOCOL_DASH_LIVE;
            player.openDashUrl(formatInfo.getDashManifestUrl());
        } else if (formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            Log.d(TAG, "Loading live video (current or past live stream) in hls format...");
            mFormatProtocol = PlayerFailure.PROTOCOL_HLS;
            player.openHlsUrl(formatInfo.getHlsManifestUrl());
        } else if (formatInfo.containsUrlFormats()) {
            Log.d(TAG, "Loading url list video. This is always LQ...");
            mFormatProtocol = PlayerFailure.PROTOCOL_URL_LIST;
            player.openUrlList(formatInfo.createUrlList());
        } else {
            Log.d(TAG, "Empty format info received. Seems future live translation. No video data to pass to the player.");
            PlayerFailure failure = newFailure(PlayerFailure.KIND_EMPTY_FORMATS, formatInfo);
            addMediaDetails(failure, formatInfo);

            if (isLiveClientToSwitch(formatInfo)) {
                // The client says the live stream plays and has nothing to play it from (e.g. TV for some music channels): the same
                // client again would say the same
                failure.action("switch_next_client_now").action("reload_video").record();
                MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.msg_live_no_media,
                        mFormatClient != null ? mFormatClient : "YouTube"));
                YouTubeServiceManager.instance().switchNextClientNow();
                reloadVideo(0, failure.getKind());
                isRefetched = true;
            } else {
                // Not started yet: a reload when it does is all there is to do
                if (!isUpcoming(formatInfo)) {
                    failure.action("reload_video").record();
                }

                String reason = formatInfo.getPlayabilityReason();
                player.setTitle(reason != null ? reason : getContext().getString(R.string.msg_no_media_retry));
                player.showProgressBar(false);
                mSuggestionsController.loadSuggestions(getVideo());
                bgImageUrl = getVideo().getBackgroundUrl();
                player.showOverlay(true);
                reloadVideo(getEmptyFormatsReloadMs(formatInfo, System.currentTimeMillis()), failure.getKind());
            }
        }

        if (!isRefetched) {
            VideoStarts.onProtocol(mFormatProtocol); // none: the video isn't played
        }

        scheduleStreamsRefresh();

        if (mFormatProtocol != null) {
            mMembersSkips = 0; // one plays: the next run of members' videos is skipped again
        }

        // Their average bitrates, before the player's tracks are known (see FormatAttempts#onTracks)
        FormatAttempts.onFormats(mFormatProtocol != null ? formatInfo.getAdaptiveFormats() : null);
        FormatAttempts.onSource(mFormatClient, mFormatProtocol);
        player.showBackground(bgImageUrl); // remove bg (if video playing) or set another bg
    }

    /**
     * Of the video playing (or failing to): null when it has none yet
     */
    public String getFormatClient() {
        return isFormatOfVideo() ? mFormatClient : null;
    }

    /**
     * See PlayerFailure.PROTOCOL_ constants. Of the video playing (or failing to): null when it has none yet.
     */
    public String getFormatProtocol() {
        return isFormatOfVideo() ? mFormatProtocol : null;
    }

    /**
     * The formats the player has are of the video: they reached the player (see processFormatInfo). The video before's until then.
     */
    public boolean isFormatOfVideo() {
        return getVideo() != null && mFormatVideoId != null && mFormatVideoId.equals(getVideo().videoId);
    }

    /**
     * The YouTube client's name, e.g. TV
     */
    private static String getClientName(MediaItemFormatInfo formatInfo) {
        MediaItemFormatInfo.ClientInfo client = formatInfo.getClientInfo();

        if (client == null) {
            return null;
        }

        return client instanceof Enum ? ((Enum<?>) client).name() : client.getClientName();
    }

    /**
     * A premiere or a live stream that hasn't started (e.g. "Premieres in 88 minutes")
     */
    @VisibleForTesting
    static boolean isUpcoming(MediaItemFormatInfo formatInfo) {
        return STATUS_LIVE_STREAM_OFFLINE.equals(formatInfo.getPlayabilityStatus());
    }

    /**
     * Of one with no formats: every 30 s. Of a premiere or a live stream that starts later, half the time till it's to start, between
     * 30 s and 10 min, so the reload after it starts comes 30 s after at most (E3RZYG50: "Premieres in 88 minutes" was reloaded every
     * 30 s, ~176 times).
     */
    @VisibleForTesting
    static int getEmptyFormatsReloadMs(MediaItemFormatInfo formatInfo, long nowMs) {
        long startMs = isUpcoming(formatInfo) ? DateHelper.toUnixTimeMs(formatInfo.getStartTimestamp()) : 0;

        if (startMs <= nowMs) {
            return EMPTY_FORMATS_RELOAD_MS;
        }

        return (int) Math.max(EMPTY_FORMATS_RELOAD_MS, Math.min(MAX_UPCOMING_RELOAD_MS, (startMs - nowMs) / 2));
    }

    /**
     * A live stream the client says plays (not one that's yet to start), for the first few times: another client may have it
     */
    private boolean isLiveClientToSwitch(MediaItemFormatInfo formatInfo) {
        Video video = getVideo();

        if (video == null || !formatInfo.isLive() || !"OK".equals(formatInfo.getPlayabilityStatus())) {
            return false;
        }

        if (!Helpers.equals(video.videoId, mLiveSwitchVideoId)) {
            mLiveSwitchVideoId = video.videoId;
            mLiveClientSwitches = 0;
        }

        boolean isToSwitch = ++mLiveClientSwitches <= MAX_LIVE_CLIENT_SWITCHES;
        rememberLiveSwitches(video.videoId, isToSwitch);
        rememberLiveSwitches(formatInfo.getChannelId(), isToSwitch);

        return isToSwitch;
    }

    private void rememberLiveSwitches(@Nullable String id, boolean isToSwitch) {
        if (id == null) {
            return;
        }

        if (isToSwitch) {
            mLiveSwitches.put(id, mLiveClientSwitches);
        } else {
            mLiveSwitches.remove(id); // none of them had it
        }
    }

    /**
     * What the formats had, of the ones the player plays from: a live stream's answer may have none of them
     */
    private static void addMediaDetails(PlayerFailure failure, MediaItemFormatInfo formatInfo) {
        failure.detail("live", formatInfo.isLive())
                .detail("liveContent", formatInfo.isLiveContent())
                .detail("startTimeMs", formatInfo.getStartTimeMs())
                .detail("sabrFormats", formatInfo.containsSabrFormats())
                .detail("dashFormats", formatInfo.containsDashFormats())
                .detail("hlsManifest", formatInfo.containsHlsUrl())
                .detail("dashManifest", formatInfo.containsDashUrl())
                .detail("urlFormats", formatInfo.containsUrlFormats())
                .detail("serverAbrUrl", formatInfo.getServerAbrStreamingUrl() != null);
    }

    /**
     * Of the video playing: its formats are fetched again, asked of the client first and of the order's others after it, and it plays
     * on from where it is. For this video only: another one plays on the order of the settings (see resetPickedClient).
     *
     * @param client e.g. WEB, one of the order's (see MediaServiceData#getClientOrder)
     */
    public void pickClient(String client) {
        Video video = getVideo();

        if (getPlayer() == null || video == null || video.videoId == null || client == null) {
            return;
        }

        mPickedClient = client;
        mPickedClientVideoId = video.videoId;
        mIsPickedClientFallbackShown = false;
        YouTubeServiceManager.instance().pickClient(video.videoId, client);
        reloadVideo(0, VideoStart.REASON_PICK_CLIENT);
    }

    /**
     * Picked for the video playing (see pickClient), null for none
     */
    @Nullable
    public String getPickedClient() {
        Video video = getVideo();

        return video != null && Helpers.equals(video.videoId, mPickedClientVideoId) ? mPickedClient : null;
    }

    /**
     * Another video than the one a client was picked for: the order's clients again, from the next one
     */
    private void resetPickedClient(Video video) {
        if (mPickedClientVideoId == null || Helpers.equals(video.videoId, mPickedClientVideoId)) {
            return;
        }

        mPickedClient = null;
        mPickedClientVideoId = null;
        YouTubeServiceManager.instance().clearPickedClient();
    }

    /**
     * The client picked for the video didn't give the formats (it can't play it, or failed and was switched past): said once, with the
     * one that did. Not when none could: the player's title says why (see processFormatInfo).
     */
    private void showPickedClientFallback(MediaItemFormatInfo formatInfo) {
        String picked = getPickedClient();

        if (picked == null || picked.equals(mFormatClient) || formatInfo.isUnplayable() || mIsPickedClientFallbackShown) {
            return;
        }

        mIsPickedClientFallbackShown = true;
        MessageHelpers.showLongMessage(getContext(), getContext().getString(R.string.msg_client_fallback, picked,
                mFormatClient != null ? mFormatClient : "YouTube"));
    }

    /**
     * Another video than the live stream the client was switched for: the first client again, or the switch would stay for all that's
     * played till the app restarts, without what that client has (e.g. being signed in)
     */
    private void resetLiveClientSwitch(Video video) {
        if (mLiveSwitchVideoId != null && !Helpers.equals(video.videoId, mLiveSwitchVideoId)) {
            mLiveSwitchVideoId = null;
            mLiveClientSwitches = 0;
            YouTubeServiceManager.instance().resetClient();
        }

        if (mLiveSwitchVideoId == null) {
            applyKnownLiveSwitches(video);
        }
    }

    /**
     * A live stream, or one of a channel, the first client had nothing for in this run: straight to the client that came after, without
     * the fetch and the message. Only for those: the first client has what others don't (e.g. being signed in).
     */
    private void applyKnownLiveSwitches(Video video) {
        Integer switches = mLiveSwitches.get(video.videoId);

        if (switches == null && video.isLive && video.channelId != null) {
            switches = mLiveSwitches.get(video.channelId);
        }

        if (switches == null) {
            return;
        }

        mLiveSwitchVideoId = video.videoId;
        mLiveClientSwitches = switches;

        for (int i = 0; i < switches; i++) {
            YouTubeServiceManager.instance().switchNextClientNow();
        }
    }

    /**
     * Of a video that has no formats to play
     */
    private PlayerFailure newFailure(String kind, MediaItemFormatInfo formatInfo) {
        Video video = getVideo();

        return addPickedClient(PlayerFailure.create(getContext(), kind)
                .video(video)
                .source(mFormatClient, null)
                .playability(formatInfo.getPlayabilityStatus(), formatInfo.getPlayabilityReason())
                .clientAttempts(video != null ? ClientAttempts.get(video.videoId) : null));
    }

    /**
     * The client picked in the player for the video, when there's one (see pickClient): its attempt comes first, not the order's
     */
    PlayerFailure addPickedClient(PlayerFailure failure) {
        String picked = getPickedClient();

        return picked != null ? failure.detail("pickedClient", picked) : failure;
    }

    /**
     * @param reason why, for the start it makes (see VideoStart#reason): e.g. the kind of failure the error fixer acted on
     */
    public void reloadVideo(int delayMs, @Nullable String reason) {
        mNextReloadReason = reason;
        reloadVideo(delayMs);
    }

    /**
     * With the reason told for it (see #reloadVideo(int, String)), none otherwise
     */
    public void reloadVideo(int delayMs) {
        String reason = mNextReloadReason;
        mNextReloadReason = null;

        if (getPlayer() == null) {
            return;
        }

        if (getPlayer().isEngineInitialized()) {
            Log.d(TAG, "Reloading the video...");
            mReloadReason = reason;
            Utils.postDelayed(mReloadVideo, delayMs);

            if (delayMs >= 0) { // posted (see Utils#postDelayed)
                mReloadDueMs = SystemClock.elapsedRealtime() + delayMs;
            }
        }
    }

    private void loadNextVideo(int delayMs) {
        if (getPlayer() == null) {
            return;
        }

        if (getPlayer().isEngineInitialized()) {
            Log.d(TAG, "Starting the next video...");
            Utils.postDelayed(mLoadNext, delayMs);
        }
    }

    /**
     * @param delayMs e.g. longer each time a restart didn't help (see ErrorFixerController)
     * @param reason why, for the start it makes (see VideoStart#reason): e.g. the kind of failure the error fixer acted on
     */
    void restartEngine(int delayMs, @Nullable String reason) {
        mNextRestartReason = reason;
        restartEngine(delayMs);
    }

    /**
     * With the reason told for it (see #restartEngine(int, String)), none otherwise
     */
    void restartEngine(int delayMs) {
        String reason = mNextRestartReason;
        mNextRestartReason = null;

        if (getPlayer() != null) {
            Log.d(TAG, "Restarting the engine...");
            mRestartReason = reason;
            Utils.postDelayed(mRestartEngine, delayMs);

            if (delayMs >= 0) { // posted (see Utils#postDelayed)
                mRestartDueMs = SystemClock.elapsedRealtime() + delayMs;
            }
        }
    }

    /**
     * A reload of the video or a restart of the engine was asked for and hasn't run yet: the player it replaces may still fail on its
     * way out (see ErrorFixerController#runEngineErrorAction)
     */
    boolean isFixPending() {
        long nowMs = SystemClock.elapsedRealtime();

        return mReloadDueMs >= 0 && nowMs < mReloadDueMs || mRestartDueMs >= 0 && nowMs < mRestartDueMs;
    }

    /**
     * @param trigger of the start of the video, see VideoStart#TRIGGER_NEXT
     */
    private void openVideoInt(Video item, String trigger) {
        if (item == null) {
            return;
        }

        disposeActions();

        if (item.hasVideo()) {
            mStartVideo = item;
            mStartTrigger = trigger;

            if (!isEmbedPlayer()) { // a card's preview isn't timed
                VideoStarts.start(item.videoId, trigger);
            }

            // NOTE: Next clicked: instant playback even a mix
            // NOTE: Bypass PIP fullscreen on next caused by startView
            getMainController().onNewVideo(item);
            //getPlayer().showOverlay(true);
        } else {
            VideoActionPresenter.instance(getContext()).apply(item);
        }
    }

    /**
     * Not played: only the channel's members watch it, and the account can't (its formats) or the Members setting hides it (its
     * metadata). Started by autoplay or the Next button, the next one plays, as autoplay goes on, past the ones known to be such (see
     * MembersVideos). Opened by the user (a card, a link, the phone), it stays, with the reason. At most MAX_MEMBERS_SKIPS in a row.
     * Its formats failed before its metadata loaded (that loads once a video plays, see SuggestionsController.onVideoLoaded): what's
     * next isn't known, its metadata is loaded first, then the next one plays (see SuggestionsController.syncCurrentVideo).
     *
     * @return whether the next one is played, now or once its metadata loaded
     */
    public boolean skipMembersVideo(Video video) {
        boolean isStartedAsNext = video != null && mStartVideo != null && Helpers.equals(video.videoId, mStartVideo.videoId)
                && Helpers.equalsAny(mStartTrigger, VideoStart.TRIGGER_AUTOPLAY, VideoStart.TRIGGER_NEXT);

        if (getPlayer() == null || !isStartedAsNext) {
            return false;
        }

        // Skipped already, before its metadata said what's next: it has now
        boolean isPending = isMembersSkipPending(video);
        mMembersSkipVideoId = null;

        if (!isPending && mMembersSkips >= MAX_MEMBERS_SKIPS) {
            return false;
        }

        boolean hasNext = mSuggestionsController.getNext() != null;

        // Nothing to play instead (e.g. its suggestions are hidden too): it stays
        if (!hasNext && (isPending || video.isSynced)) {
            return false;
        }

        if (!isPending) {
            mMembersSkips++;
        }

        if (hasNext) {
            loadNext(VideoStart.TRIGGER_AUTOPLAY);
        } else {
            mMembersSkipVideoId = video.videoId;
            mSuggestionsController.loadSuggestions(video);
        }

        return true;
    }

    /**
     * Skipped before its metadata loaded, which says what's next (see skipMembersVideo)
     */
    public boolean isMembersSkipPending(@Nullable Video video) {
        return video != null && mMembersSkipVideoId != null && mMembersSkipVideoId.equals(video.videoId);
    }

    private boolean isActionsRunning() {
        return RxHelper.isAnyActionRunning(mFormatInfoAction);
    }

    private void disposeActions() {
        MediaServiceManager.instance().disposeActions();
        RxHelper.disposeActions(mFormatInfoAction, mMusicAutoplayAction, mRefreshStreamsAction);
        Utils.removeCallbacks(mReloadVideo, mLoadNext, mRestartEngine, mMetadataSync, mPrefetchNext, mRefreshStreams);
        mReloadDueMs = -1;
        mRestartDueMs = -1;
    }

    public void restartEngine() {
        restartEngine((String) null);
    }

    public void restartEngine(@Nullable String reason) {
        restartEngine(1_000, reason);
    }

    public void reloadVideo() {
        reloadVideo((String) null);
    }

    public void reloadVideo(@Nullable String reason) {
        reloadVideo(1_000, reason);
    }

    private void applyPlaybackMode(int playbackMode) {
        if (getPlayer() == null) {
            return;
        }

        Video video = getVideo();
        // Fix simultaneous videos loading (e.g. when playback ends and user opens new video)
        if (video == null || isActionsRunning()) {
            return;
        }

        if (isEmbedPlayer()) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_CLOSE;
        } else if (isNextVideoMode(video, playbackMode) && MusicAutoplayManager.isMusicVideo(getContext(), video)) {
            applyMusicPlaybackMode(video, playbackMode);
            return;
        }

        applyPlaybackModeInt(video, playbackMode);
    }

    private void applyPlaybackModeInt(Video video, int playbackMode) {
        int step = getEndStep(video, playbackMode);

        if (step == END_NEXT) {
            loadNext(VideoStart.TRIGGER_AUTOPLAY);
            return;
        }

        if (step == END_PREVIOUS) {
            onPreviousClicked();
            return;
        }

        switch (playbackMode) {
            case PlayerConstants.PLAYBACK_MODE_REVERSE_LIST:
                // The first video of its list or channel: stops after it
                break;
            case PlayerConstants.PLAYBACK_MODE_ONE:
                if (VERSION.SDK_INT <= 19) {
                    // Fix frozen image on Android 4
                    restartEngine(VideoStart.REASON_REPEAT);
                } else if (!getPlayer().containsMedia()) {
                    // An error ended it (see ErrorFixerController): a seek doesn't play an idle player. Reloaded from its start.
                    getStateService().save(new State(video, 0, getPlayer().getDurationMs(), getPlayer().getSpeed()));
                    reloadVideo(VideoStart.REASON_REPEAT);
                } else {
                    getPlayer().setPositionMs(0);
                }
                break;
            case PlayerConstants.PLAYBACK_MODE_CLOSE:
                // Close player if suggestions not shown
                AppDialogPresenter dialog = getAppDialogPresenter();
                if (!getPlayer().isSuggestionsShown() && (!dialog.isDialogShown() || dialog.isOverlay())) {
                    dialog.closeDialog();
                    getPlayer().finishReally();
                }
                break;
            case PlayerConstants.PLAYBACK_MODE_PAUSE:
                // Stop player after each video.
                stopPlayback();
                break;
            case PlayerConstants.PLAYBACK_MODE_LIST:
                // The end of the playlist
                //restartPlaylistIfNeeded();
                stopPlayback();
                break;
            default:
                Log.e(TAG, "Undetected repeat mode " + playbackMode);
                break;
        }
    }

    /**
     * Whether the end of the video in the mode opens another video (see applyPlaybackModeInt): END_NEXT, END_PREVIOUS, or END_STAY
     * for what the mode does with this one (repeats it, stops, closes…)
     */
    private int getEndStep(Video video, int playbackMode) {
        switch (playbackMode) {
            case PlayerConstants.PLAYBACK_MODE_REVERSE_LIST:
                if (video.hasPlaylist() || video.belongsToChannelUploads() || video.belongsToChannel()) {
                    VideoGroup group = video.getGroup();
                    // stop after first
                    return group != null && group.indexOf(video) != 0 ? END_PREVIOUS : END_STAY;
                }
                return END_NEXT;
            case PlayerConstants.PLAYBACK_MODE_ALL:
            case PlayerConstants.PLAYBACK_MODE_SHUFFLE:
                return END_NEXT;
            case PlayerConstants.PLAYBACK_MODE_CLOSE:
            case PlayerConstants.PLAYBACK_MODE_PAUSE:
                // Except when playing from queue
                return mPlaylist.getNext() != null && !getPlayerTweaksData().isQueueRespectsPlaybackMode() ? END_NEXT : END_STAY;
            case PlayerConstants.PLAYBACK_MODE_LIST:
                // if video has a playlist load next
                return video.hasNextPlaylist() || mPlaylist.getNext() != null ? END_NEXT : END_STAY;
            default:
                return END_STAY;
        }
    }

    /**
     * A music video goes on to the next one only when it's music too, the playback mode of the other videos decides otherwise
     * (e.g. a mix whose next video is a vlog stops with Pause after each video).<br/>
     * Waits for the category of the next video when it isn't known yet: it's usually looked up with the metadata (see resolveMusicCategory).
     * A video still unknown after that isn't music.
     */
    private void applyMusicPlaybackMode(Video video, int playbackMode) {
        Video next = mSuggestionsController.getNext();
        // Not ended when it's the Next button of the child mode (see onNextClicked)
        boolean isEnded = isEnded();

        RxHelper.disposeActions(mMusicAutoplayAction);
        mMusicAutoplayAction = RxHelper.execute(
                VideoCategoryManager.instance(getContext()).resolveVideos(Collections.singletonList(next != null ? next.videoId : null)),
                error -> onNextVideoResolved(video, isEnded, playbackMode),
                () -> onNextVideoResolved(video, isEnded, playbackMode));
    }

    private void onNextVideoResolved(Video video, boolean isEnded, int playbackMode) {
        // Another video was opened, or the user went on with this one meanwhile
        if (getPlayer() == null || video != getVideo() || (isEnded && !isEnded())) {
            return;
        }

        // The next video may have changed meanwhile (e.g. added to the queue)
        if (!isNextMusic()) {
            Log.d(TAG, "Music autoplay: the next video isn't music, the playback mode of the other videos applies");
            playbackMode = getPlayerData().getPlaybackMode();
        }

        applyPlaybackModeInt(video, playbackMode);
    }

    /**
     * The category of the next video is known, and it's music (see applyMusicPlaybackMode)
     */
    private boolean isNextMusic() {
        Video next = mSuggestionsController.getNext();

        return next != null && VideoCategoryManager.instance(getContext()).isMusic(next.videoId);
    }

    /**
     * The video the end of this one opens by itself, as applyPlaybackMode decides it now: null for none (e.g. it repeats, stops after
     * it, or nothing's known to come next yet). A music video with Music autoplay goes on in the mode of the music videos only to
     * music, with the category of the next video known by now (see resolveMusicCategory). Not an embed's: it's a card's preview.
     */
    @Nullable
    private Video getVideoAtEnd() {
        Video video = getVideo();

        if (video == null || isEmbedPlayer()) {
            return null;
        }

        int playbackMode = getPlaybackMode();

        if (isNextVideoMode(video, playbackMode) && MusicAutoplayManager.isMusicVideo(getContext(), video) && !isNextMusic()) {
            playbackMode = getPlayerData().getPlaybackMode();
        }

        switch (getEndStep(video, playbackMode)) {
            case END_NEXT:
                return mSuggestionsController.getNext();
            case END_PREVIOUS:
                return mSuggestionsController.getPrevious();
            default:
                return null;
        }
    }

    /**
     * About 20 s of the video before its end (in real time, at its speed), the formats of the one the end opens are fetched: that video
     * starts without a fetch (see FormatInfoWrapper#prefetchFormatInfo). Looked at again when the time left changes (it plays again, a
     * seek, the speed), and when the next video may have (the metadata, the category of the next video). Only while it plays: not over
     * a video that starts or buffers.
     */
    private void prefetchNextIfNeeded() {
        Utils.removeCallbacks(mPrefetchNext);

        PlaybackView player = getPlayer();
        Video video = getVideo();

        if (player == null || video == null || video.isLive || !player.isPlaying()) {
            return;
        }

        long durationMs = player.getDurationMs();

        if (durationMs <= 0) {
            return;
        }

        float speed = player.getSpeed() > 0 ? player.getSpeed() : 1.0f;
        long untilMs = (long) ((durationMs - player.getPositionMs() - PREFETCH_BEFORE_END_MS) / speed);

        if (untilMs > 0) {
            Utils.postDelayed(mPrefetchNext, untilMs);
            return;
        }

        prefetchNext(getVideoAtEnd());
    }

    /**
     * One at a time: when it's done, the video at the end is looked at again, it may have changed meanwhile (e.g. one added to the
     * queue). Once per video while this one plays: one that failed is fetched by its start, as without it.
     * Not while offline, nor when a poToken would be made: its WebView runs on the main thread mostly, the start of the video makes it.
     */
    private void prefetchNext(@Nullable Video next) {
        Video video = getVideo();

        if (next == null || next.videoId == null || !next.hasVideo() || next.isLive || next.isUpcoming || video == null
                || next.videoId.equals(video.videoId) || next.videoId.equals(mNextPrefetchVideoId)
                || RxHelper.isAnyActionRunning(mNextPrefetchAction) || !isPrefetchAllowed()) {
            return;
        }

        Log.d(TAG, "Fetching the formats of the video the end opens: %s", next.videoId);
        mNextPrefetchVideoId = next.videoId;
        mNextPrefetchAction = RxHelper.execute(getPrefetchObserve(next.videoId),
                error -> Log.e(TAG, "Can't fetch the formats of the next video: %s", error.getMessage()),
                this::prefetchNextIfNeeded);
    }

    @VisibleForTesting
    boolean isPrefetchAllowed() {
        return ValidatedNetwork.isAvailable() && !YouTubeServiceManager.instance().isPoTokenToMake();
    }

    @VisibleForTesting
    Observable<Void> getPrefetchObserve(String videoId) {
        return YouTubeServiceManager.instance().getMediaItemService().prefetchFormatInfoObserve(videoId);
    }

    /**
     * The mode goes on to the next video (the queue aside). The reverse order is the next video too outside a playlist or a channel.
     */
    private boolean isNextVideoMode(Video video, int playbackMode) {
        return playbackMode == PlayerConstants.PLAYBACK_MODE_ALL || playbackMode == PlayerConstants.PLAYBACK_MODE_SHUFFLE ||
                (playbackMode == PlayerConstants.PLAYBACK_MODE_LIST && video.hasNextPlaylist()) ||
                (playbackMode == PlayerConstants.PLAYBACK_MODE_REVERSE_LIST &&
                        !(video.hasPlaylist() || video.belongsToChannelUploads() || video.belongsToChannel()));
    }

    private boolean isEnded() {
        return getPlayer() != null && getPlayer().getPositionMs() >= getPlayer().getDurationMs();
    }

    /**
     * Music autoplay: the current video is looked up when it's loaded, the next one with the metadata. The player button shows the mode
     * of the music videos once the current one is known to be music, and the end of the video doesn't wait for the next one.
     */
    private void resolveMusicCategory(Video item) {
        Video current = getVideo();

        if (item == null || current == null || !MusicAutoplayManager.isEnabled(getContext())) {
            return;
        }

        // Not disposed with the video: the categories are kept for later
        RxHelper.execute(VideoCategoryManager.instance(getContext()).resolveVideos(Collections.singletonList(item.videoId)),
                () -> {
                    if (current != getVideo()) {
                        return;
                    }

                    updateRepeatButton();
                    // The mode that applies at its end may be another one now
                    prefetchNextIfNeeded();

                    // The metadata came before the video was known to be music, with another mode (see onMetadata)
                    if (Helpers.equals(item.videoId, current.videoId) && current.playlistInfo != null && !current.isShuffled &&
                            MusicAutoplayManager.getPlaybackMode(getContext(), current) == PlayerConstants.PLAYBACK_MODE_SHUFFLE) {
                        initRandomNext();
                    }
                });
    }

    /**
     * The playback mode of the video: a music video has its own with Music autoplay on (see MusicAutoplayManager)
     */
    private void updateRepeatButton() {
        Video video = getVideo();

        if (getPlayer() == null || video == null) {
            return;
        }

        getPlayer().setButtonState(R.id.action_repeat, video.finishOnEnded ? PlayerConstants.PLAYBACK_MODE_CLOSE :
                MusicAutoplayManager.getPlaybackMode(getContext(), video));
    }

    private void stopPlayback() {
        if (getPlayer() == null) {
            return;
        }

        getPlayer().setPositionMs(getStopPositionMs());
        getPlayer().setPlayWhenReady(false);
        getPlayer().showSuggestions(true);
    }

    /**
     * A second before the end of a video. At its length the player is past the media (e.g. 1972 s of 9lKpEOEbr0U's 1971.719 s)
     * and over DASH never gets ready there: the stall check reloaded the video, from 0:00. A live stream is left at its end
     */
    private long getStopPositionMs() {
        long durationMs = getPlayer().getDurationMs();
        Video video = getVideo();

        if ((video != null && video.isLive) || durationMs <= STOP_BEFORE_END_MS) {
            return durationMs;
        }

        return durationMs - STOP_BEFORE_END_MS;
    }

    private void restartPlaylistIfNeeded() {
        if (getPlayer() == null || getVideo() == null) {
            return;
        }
        
        VideoGroup group = getVideo().getGroup(); // Get the VideoGroup (playlist)

        if (group != null && !group.isEmpty() && getVideo().belongsToSamePlaylistGroup()) {
            openVideoInt(group.get(0), VideoStart.TRIGGER_AUTOPLAY);
        } else {
            Log.e(TAG, "VideoGroup is null or empty. Can't restart playlist.");
            stopPlayback();
        }
    }

    private boolean acceptAdaptiveFormats(MediaItemFormatInfo formatInfo) {
        if (getPlayerData().isLegacyCodecsForced() && formatInfo.containsUrlFormats()) {
            return false;
        }

        if (getPlayerTweaksData().isHlsStreamsForced() && formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            return false;
        }

        // Not enough info for full length live streams
        if (formatInfo.isLive() && formatInfo.getStartTimeMs() == 0) {
            return false;
        }

        // Live dash url doesn't work with None buffer
        //if (formatInfo.isLive() && (getPlayerTweaksData().isDashUrlStreamsForced() || getPlayerData().getVideoBufferType() == PlayerData.BUFFER_NONE)) {
        if (formatInfo.isLive() && getPlayerTweaksData().isDashUrlStreamsForced() && formatInfo.containsDashUrl()) {
            return false;
        }

        if (formatInfo.isLive() && getPlayerTweaksData().isHlsStreamsForced() && formatInfo.containsHlsUrl()) {
            return false;
        }

        return true;
    }

    private boolean acceptDashLive(MediaItemFormatInfo formatInfo) {
        if (getPlayerTweaksData().isHlsStreamsForced() && formatInfo.isLive() && formatInfo.containsHlsUrl()) {
            return false;
        }

        return formatInfo.isLive() && formatInfo.containsDashUrl();
    }

    @Override
    public void onMetadata(MediaItemMetadata metadata) {
        initRandomNext();
        resolveMusicCategory(mSuggestionsController.getNext());
        prefetchNextIfNeeded();
    }

    private void initRandomNext() {
        MediaServiceManager.instance().disposeActions();

        PlaybackView player = getPlayer();
        PlayerData playerData = getPlayerData();
        Video current = getVideo();

        if (player == null || playerData == null || current == null || current.playlistInfo == null ||
                MusicAutoplayManager.getPlaybackMode(getContext(), current) != PlayerConstants.PLAYBACK_MODE_SHUFFLE) {
            return;
        }

        // NOTE: Shuffle only user created playlists (size != -1)
        if (current.playlistInfo.getSize() > MIN_SHUFFLE_SIZE) {
            Video video = new Video();
            video.playlistId = current.playlistId;
            video.playlistIndex = Utils.getRandomIndex(current.playlistInfo.getCurrentIndex(), current.playlistInfo.getSize());
            MediaServiceManager.instance().loadMetadata(video, randomMetadata -> {
                if (randomMetadata.getNextVideo() == null) {
                    return;
                }

                current.nextMediaItem = SimpleMediaItem.from(randomMetadata);
                current.isShuffled = true;
                player.setNextTitle(Video.from(current.nextMediaItem));
            });
        }
        //else {
        //    VideoGroup topRow = player.getSuggestionsByIndex(0); // the playlist row
        //
        //    if (topRow != null && topRow.isChapters()) {
        //        topRow = player.getSuggestionsByIndex(1);
        //    }
        //
        //    if (topRow != null) {
        //        int currentIdx = topRow.indexOf(current);
        //        int randomIndex = Utils.getRandomIndex(currentIdx, topRow.getSize());
        //
        //        if (randomIndex != -1) {
        //            Video nextVideo = topRow.get(randomIndex);
        //            current.nextMediaItem = SimpleMediaItem.from(nextVideo);
        //            current.isShuffled = true;
        //            player.setNextTitle(nextVideo);
        //        }
        //    }
        //}
    }

    private int getPlaybackMode() {
        Video video = getVideo();
        int playbackMode = MusicAutoplayManager.getPlaybackMode(getContext(), video);

        if (video != null && video.finishOnEnded) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_CLOSE;
        } else if (video != null && video.belongsToShortsGroup() && getPlayerTweaksData().isLoopShortsEnabled()) {
            playbackMode = PlayerConstants.PLAYBACK_MODE_ONE;
        }
        return playbackMode;
    }

    /**
     * Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
     */
    private void applyAspectRatio(MediaItemFormatInfo formatInfo) {
        if (getPlayer() == null) {
            return;
        }

        // Fix stretched video for a couple milliseconds (before the onVideoSizeChanged gets called)
        if (formatInfo.containsDashFormats()) {
            MediaFormat format = formatInfo.getAdaptiveFormats().get(0);
            int width = format.getWidth();
            int height = format.getHeight();
            boolean isShorts = width < height;
            if (width > 0 && height > 0 && (getPlayerData().getAspectRatio() == PlayerData.ASPECT_RATIO_DEFAULT || isShorts)) {
                getPlayer().setAspectRatio((float) width / height);
            } else {
                getPlayer().setAspectRatio(getPlayerData().getAspectRatio());
            }
        }
    }
}
