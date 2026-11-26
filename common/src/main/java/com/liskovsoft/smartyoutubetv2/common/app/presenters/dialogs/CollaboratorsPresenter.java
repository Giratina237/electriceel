package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.googlecommon.common.helpers.YouTubeHelper;
import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCallback;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.BlockedChannelManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;

import io.reactivex.Observable;

/**
 * The channels of a collaboration (see Video.collaborators), as YouTube's Collaborators panel: a row per channel with its avatar,
 * handle and subscribers, and a line telling whether it's subscribed to or blocked. A row opens the channel's own list: open the channel, subscribe
 * or unsubscribe, block or unblock it. After a change the panel is shown again, the channel's row focused.
 */
public class CollaboratorsPresenter extends BasePresenter<Void> {
    private final AppDialogPresenter mDialogPresenter;
    private final MediaItemService mMediaItemService;

    public interface ChannelCallback {
        void onChannel(Video channel);
    }

    public interface Listener {
        /**
         * The panel is closed first
         */
        void onOpenChannel(Video channel);
        /**
         * Its isSubscribed tells the new state
         */
        void onSubscriptionChanged(Video channel);
    }

    private CollaboratorsPresenter(Context context) {
        super(context);
        mDialogPresenter = AppDialogPresenter.instance(context);
        mMediaItemService = YouTubeServiceManager.instance().getMediaItemService();
    }

    public static CollaboratorsPresenter instance(Context context) {
        return new CollaboratorsPresenter(context);
    }

    /**
     * @param collaborators the channels, as channel items (see Video.from(Collaborator))
     */
    public void show(List<Video> collaborators, Listener listener) {
        show(collaborators, listener, null);
    }

    /**
     * @param focused the channel whose row has the focus: the panel takes the place of the lists shown, after a change in them
     */
    private void show(List<Video> collaborators, Listener listener, Video focused) {
        OptionItem focusedItem = null;

        for (Video channel : collaborators) {
            OptionItem item = createRow(channel, option -> showChannel(collaborators, channel, listener));
            mDialogPresenter.appendSingleButton(item);

            if (channel == focused) {
                focusedItem = item;
            }
        }

        if (focusedItem != null) {
            mDialogPresenter.setFocusedItem(focusedItem);
            mDialogPresenter.enableReplace(true);
        }

        mDialogPresenter.showDialog(getContext().getString(R.string.collaborators));
    }

    /**
     * The channels as the panel's rows, under the title: picking one calls back (e.g. Block the channel of a video's menu, see
     * VideoMenuPresenter). Over the lists shown.
     */
    public void pickChannel(List<Video> collaborators, CharSequence title, ChannelCallback onPicked) {
        for (Video channel : collaborators) {
            mDialogPresenter.appendSingleButton(createRow(channel, option -> onPicked.onChannel(channel)));
        }

        mDialogPresenter.showDialog(title);
    }

    private OptionItem createRow(Video channel, OptionCallback onSelect) {
        OptionItem item = UiOptionItem.from(channel.getAuthor(), getSummary(channel), onSelect);
        item.setIconUrl(channel.cardImageUrl);
        return item;
    }

    private void showChannel(List<Video> collaborators, Video channel, Listener listener) {
        boolean isBlocked = BlockedChannelManager.instance(getContext()).isBlocked(channel);

        mDialogPresenter.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.open_channel), option -> {
            mDialogPresenter.closeDialog();
            listener.onOpenChannel(channel);
        }));
        mDialogPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(channel.isSubscribed ? R.string.unsubscribe_from_channel : R.string.subscribe_to_channel), option -> {
                    toggleSubscribe(channel);
                    listener.onSubscriptionChanged(channel);
                    show(collaborators, listener, channel);
                }));
        mDialogPresenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(isBlocked ? R.string.dialog_unblock_channel : R.string.dialog_block_channel), option -> {
                    toggleBlock(channel, isBlocked);
                    show(collaborators, listener, channel);
                }));

        mDialogPresenter.showDialog(channel.getAuthor());
    }

    /**
     * E.g. "@handle • 286K subscribers" over "Subscribed • Channel blocked". The second line is always there (the player's Subscribe
     * button says "Unsubscribed" too), so a row keeps its height when the channel is subscribed to or blocked from it.
     */
    private CharSequence getSummary(Video channel) {
        CharSequence channelInfo = YouTubeHelper.createInfo(channel.channelHandle, channel.subscriberCount);
        CharSequence status = YouTubeHelper.createInfo(
                getContext().getString(channel.isSubscribed ? R.string.subscribed_to_channel : R.string.unsubscribed_from_channel),
                BlockedChannelManager.instance(getContext()).isBlocked(channel) ? getContext().getString(R.string.channel_blocked) : null);

        return channelInfo != null ? TextUtils.concat(channelInfo, "\n", status) : status;
    }

    /**
     * As the video's menu does it (see VideoMenuPresenter.toggleSubscribe)
     */
    private void toggleSubscribe(Video channel) {
        Observable<Void> observable = channel.isSubscribed ?
                mMediaItemService.unsubscribeObserve(channel.channelId) : mMediaItemService.subscribeObserve(channel.channelId);

        RxHelper.execute(observable);

        channel.isSubscribed = !channel.isSubscribed;

        MessageHelpers.showMessage(getContext(), channel.isSubscribed ? R.string.subscribed_to_channel : R.string.unsubscribed_from_channel);
    }

    /**
     * As the video's menu does it (see VideoMenuPresenter.appendBlockChannelButton): by the channel's id, so the collaborations it's in
     * go too (see BlockedChannelManager.isBlocked)
     */
    private void toggleBlock(Video channel, boolean isBlocked) {
        BlockedChannelManager manager = BlockedChannelManager.instance(getContext());
        BlockedChannelData data = BlockedChannelData.instance(getContext());

        if (isBlocked) {
            manager.unblock(channel);
            MessageHelpers.showMessage(getContext(), R.string.channel_unblocked);
        } else {
            manager.block(channel);
            MessageHelpers.showMessage(getContext(), R.string.channel_blocked);
        }

        if (data.getChannelCount() == 1) { // show on add first channel
            BrowsePresenter.instance(getContext()).enableSection(MediaGroup.TYPE_BLOCKED_CHANNELS, true);
        } else if (data.isEmpty()) { // hide on remove all
            BrowsePresenter.instance(getContext()).enableSection(MediaGroup.TYPE_BLOCKED_CHANNELS, false);
        }
    }
}
