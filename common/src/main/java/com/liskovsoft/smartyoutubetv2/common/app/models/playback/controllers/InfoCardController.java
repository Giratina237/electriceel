package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.InfoCardItem;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.QrCodePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.prefs.InfoCardData;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.reactivex.disposables.Disposable;

/**
 * Shows the cards a creator puts partway through a video (YouTube's info cards), if enabled (see InfoCardData): each one's teaser,
 * for five seconds from when playback reaches the card (see TeaserTimer). Up picks it while it shows.<br/>
 * The cards are asked of the web client once the video is loaded (see MediaItemService#getInfoCardsObserve): none of the player's
 * clients sends them.<br/>
 * None in Child mode: like the suggestions, the cards lead to other videos and channels. None on live streams and shorts.
 */
public class InfoCardController extends BasePlayerController implements OnDataChange {
    private static final String TAG = InfoCardController.class.getSimpleName();
    private static final long POLL_INTERVAL_MS = 500;
    /**
     * How long a teaser stays on the screen, in real time (see TeaserTimer)
     */
    static final long SHOW_MS = 5_000;
    private InfoCardData mData;
    private String mVideoId;
    // Of mVideoId, null while not known
    @Nullable
    private List<InfoCardItem> mItems;
    private boolean mIsFetchStarted;
    @Nullable
    private InfoCardItem mShown;
    // Of mVideoId
    private TeaserTimer mTeaserTimer = new TeaserTimer();
    private Disposable mPollAction;
    private Disposable mFetchAction;

    @Override
    public void onInit() {
        mData = InfoCardData.instance(getContext());
        mData.setOnChange(this);
    }

    @Override
    public void onNewVideo(Video item) {
        // FIX83: temporary
        Log.d(TAG, "FIX83 onNewVideo %s", item != null ? item.videoId : null);
        // Till the new one is loaded: the player still tells the position of the video before
        stopPolling();
        // The card of the video before goes at once, e.g. after a click on it
        show(null);
    }

    @Override
    public void onVideoLoaded(Video item) {
        // FIX83: temporary
        Log.d(TAG, "FIX83 onVideoLoaded %s (was %s)", item != null ? item.videoId : null, mVideoId);

        if (item != null && !Helpers.equals(mVideoId, item.videoId)) {
            reset(item.videoId);
        }

        startPolling();
    }

    @Override
    public void onEngineReleased() {
        // FIX83: temporary
        Log.d(TAG, "FIX83 onEngineReleased");
        stopPolling();
        show(null);
    }

    @Override
    public void onDataChange() {
        startPolling();
    }

    @Override
    public void onInfoCardClicked(InfoCardItem item) {
        if (item == null) {
            return;
        }

        if (item.getType() == InfoCardItem.TYPE_LINK) {
            // A TV can't open a site: the QR code opens it on a phone
            QrCodePresenter.instance(getContext()).show(item.getUrl());
            return;
        }

        // As a suggestion: a video plays (in its playlist), a channel or a playlist page opens
        Video video = toVideo(item);

        if (video != null) {
            getMainController().onSuggestionItemClicked(video);
        }
    }

    /**
     * Of the video loaded (see onVideoLoaded): live and shorts are known from its formats by then
     */
    private boolean isEnabled() {
        Video video = getVideo();

        return video != null && !video.isLive && !video.isShorts && !video.belongsToShortsGroup() && mData != null && mData.isShowEnabled()
                && !getGeneralData().isChildModeEnabled() && !isEmbedPlayer();
    }

    private void reset(String videoId) {
        RxHelper.disposeActions(mFetchAction);
        mVideoId = videoId;
        mItems = null;
        mIsFetchStarted = false;
        mTeaserTimer = new TeaserTimer();
    }

    /**
     * Once the video is loaded (see onVideoLoaded): before, the player tells the position of the video before. The cards are asked for
     * then, the first time.
     */
    private void startPolling() {
        stopPolling();

        // Not outside the player (e.g. the setting changed with the account): the next video starts it
        if (getPlayer() == null || !getPlayer().isEngineInitialized() || !isEnabled() || mVideoId == null
                || (mItems != null && mItems.isEmpty())) {
            // FIX83: temporary
            Log.d(TAG, "FIX83 startPolling: not polling (player %s, enabled %s, video %s, items %s)", getPlayer() != null,
                    isEnabled(), mVideoId, mItems != null ? mItems.size() : null);
            show(null);
            return;
        }

        if (mItems == null) {
            fetch();
        }

        mPollAction = RxHelper.interval(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)
                .subscribe(tick -> update(), error -> Log.e(TAG, "Poll error: %s", error.getMessage()));
        update();
    }

    private void stopPolling() {
        RxHelper.disposeActions(mPollAction);
    }

    private boolean isPolling() {
        return mPollAction != null && !mPollAction.isDisposed();
    }

    private void update() {
        PlaybackView player = getPlayer();
        Video video = getVideo();

        // The player may still play the video before while the next one loads
        if (player == null || video == null || !Helpers.equals(video.videoId, mVideoId) || mItems == null) {
            return;
        }

        long positionMs = player.getPositionMs();
        InfoCardItem item = mTeaserTimer.update(mItems, positionMs, SystemClock.elapsedRealtime(), player.isInfoCardShown());

        // FIX83: temporary, the position on every poll around a teaser
        if (item != null || mShown != null) {
            Log.d(TAG, "FIX83 poll position %s, speed %s, teaser %s", positionMs, player.getSpeed(), fix83Name(item));
        }

        show(item);
    }

    // FIX83: temporary
    static String fix83Name(@Nullable InfoCardItem item) {
        return item != null ? "'" + item.getTeaser() + "' @" + item.getStartMs() : "none";
    }

    private void fetch() {
        if (mIsFetchStarted) {
            return;
        }

        mIsFetchStarted = true;
        String videoId = mVideoId;

        mFetchAction = getMediaItemService().getInfoCardsObserve(videoId)
                .subscribe(items -> {
                    if (!Helpers.equals(videoId, mVideoId)) {
                        return;
                    }

                    mItems = filterCards(items);
                    Log.d(TAG, "%s: %s info cards", videoId, mItems.size());

                    if (mItems.isEmpty()) {
                        stopPolling();
                    } else if (isPolling()) {
                        // Not once it stopped meanwhile (e.g. the setting turned off): nothing would hide the teaser
                        update();
                    }
                }, error -> {
                    Log.e(TAG, "%s: can't load the info cards: %s", videoId, error.getMessage());

                    if (Helpers.equals(videoId, mVideoId)) {
                        mItems = Collections.emptyList();
                        stopPolling();
                    }
                });
    }

    private void show(@Nullable InfoCardItem item) {
        if (item == mShown) {
            return;
        }

        // FIX83: temporary
        Log.d(TAG, "FIX83 show %s -> %s, position %s, from %s", fix83Name(mShown), fix83Name(item),
                getPlayer() != null ? getPlayer().getPositionMs() : -2, new Throwable().getStackTrace()[1].getMethodName());

        mShown = item;

        if (getPlayer() != null) {
            getPlayer().setInfoCard(item);
        }
    }

    /**
     * Which card's teaser is on the screen. It shows once playback reaches the card, for {@link InfoCardController#SHOW_MS} of real
     * time: the same at any playback speed, where YouTube's time for it is the video's. Only the time it can be seen counts: one that
     * comes while the controls are up (e.g. right after a seek) gets its whole time once they're gone.<br/>
     * It doesn't come back while the position is still where YouTube would show it (e.g. paused on it), only when played into again
     * (e.g. after a seek back). It goes early when the video goes back before the card or past its end. Of two at once, the one that
     * started last.
     */
    static final class TeaserTimer {
        // The cards shown since the position came where YouTube shows their teaser
        private final Set<InfoCardItem> mShownItems = new HashSet<>();
        @Nullable
        private InfoCardItem mItem;
        // How long mItem's teaser could be seen so far, and when that was last counted
        private long mSeenMs;
        private long mCountedAtMs;

        /**
         * @param nowMs real time, e.g. SystemClock#elapsedRealtime
         * @param isSeen whether the teaser on the screen can be seen: its time only counts then, not while e.g. the controls hide it
         */
        @Nullable
        InfoCardItem update(List<InfoCardItem> items, long positionMs, long nowMs, boolean isSeen) {
            InfoCardItem latest = null;
            boolean hasNew = false;

            for (InfoCardItem item : items) {
                if (!isTeaserTime(item, positionMs)) {
                    // Played into again, it shows again
                    mShownItems.remove(item);
                    continue;
                }

                if (mShownItems.add(item)) {
                    hasNew = true;
                }

                if (latest == null || item.getStartMs() > latest.getStartMs()) {
                    latest = item;
                }
            }

            if (hasNew) {
                // FIX83: temporary
                Log.d(TAG, "FIX83 timer: new %s at position %s, now %s", fix83Name(latest), positionMs, nowMs);
                mItem = latest;
                mSeenMs = 0;
                mCountedAtMs = nowMs;
            } else if (mItem != null) {
                if (isSeen) {
                    mSeenMs += nowMs - mCountedAtMs;
                }

                mCountedAtMs = nowMs;

                if (mSeenMs >= SHOW_MS || !isActive(mItem, positionMs)) {
                    // FIX83: temporary
                    Log.d(TAG, "FIX83 timer: hide %s at position %s, seen for %s ms, active %s", fix83Name(mItem), positionMs,
                            mSeenMs, isActive(mItem, positionMs));
                    mItem = null;
                }
            }

            return mItem;
        }

        /**
         * Where YouTube shows the card's teaser, on the video's time: from its start for the teaser's time, not past its end
         */
        private static boolean isTeaserTime(InfoCardItem item, long positionMs) {
            long endMs = item.getStartMs() + item.getTeaserDurationMs();

            if (item.getEndMs() >= 0) {
                endMs = Math.min(endMs, item.getEndMs());
            }

            return positionMs >= item.getStartMs() && positionMs < endMs;
        }

        /**
         * Started and not over
         */
        private static boolean isActive(InfoCardItem item, long positionMs) {
            return positionMs >= item.getStartMs() && (item.getEndMs() < 0 || positionMs < item.getEndMs());
        }
    }

    /**
     * The cards that can be shown and opened: a known kind, with where they go and a teaser at a time
     */
    static List<InfoCardItem> filterCards(@Nullable List<InfoCardItem> items) {
        List<InfoCardItem> result = new ArrayList<>();

        if (items == null) {
            return result;
        }

        for (InfoCardItem item : items) {
            if (item != null && hasTarget(item) && item.getStartMs() >= 0 && item.getTeaserDurationMs() > 0) {
                result.add(item);
            }
        }

        return result;
    }

    private static boolean hasTarget(InfoCardItem item) {
        switch (item.getType()) {
            case InfoCardItem.TYPE_VIDEO:
                return item.getVideoId() != null;
            case InfoCardItem.TYPE_PLAYLIST:
                return item.getVideoId() != null || item.getPlaylistId() != null;
            case InfoCardItem.TYPE_CHANNEL:
                return item.getChannelId() != null;
            case InfoCardItem.TYPE_LINK:
                return item.getUrl() != null;
            default:
                return false;
        }
    }

    /**
     * The card as a suggestion would be: a video (in its playlist), a playlist page or a channel
     */
    @Nullable
    static Video toVideo(InfoCardItem item) {
        Video video = new Video();
        video.title = item.getTitle();
        video.cardImageUrl = item.getImageUrl();

        switch (item.getType()) {
            case InfoCardItem.TYPE_VIDEO:
            case InfoCardItem.TYPE_PLAYLIST:
                video.videoId = item.getVideoId();
                video.playlistId = item.getPlaylistId();
                break;
            case InfoCardItem.TYPE_CHANNEL:
                video.channelId = item.getChannelId();
                break;
            default:
                return null;
        }

        return video;
    }
}
