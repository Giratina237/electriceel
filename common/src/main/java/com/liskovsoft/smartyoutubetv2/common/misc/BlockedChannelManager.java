package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.Observable;

/**
 * Finds the channel of the cards that come with its name alone (most of Home's), so that a channel blocked with its id is matched
 * by it there too (see BlockedChannelData).<br/>
 * Their names are looked up as AiSList's are, whether its lists are on or not (see AiSListManager.resolveHandles): the cards of a
 * renamed channel are found by the id their new name has. A name can be two channels', so a card that would be hidden is then
 * looked up by its video (a watch-next request), before it's shown (see HiddenVideoResolver). Only while a channel is blocked
 * with its id.<br/>
 * A failed lookup hides the card by its name, as before the ids.<br/>
 * A collaboration ("X and Y") goes when any of its channels is blocked (e.g. from the Collaborators panel, see CollaboratorsPresenter):
 * by the ids its video was found to have, otherwise by the ones its name was.
 */
public class BlockedChannelManager {
    private static final String TAG = BlockedChannelManager.class.getSimpleName();
    private static final int MAX_VIDEOS = 1_000;
    private static final int MAX_PARALLEL_LOOKUPS = 4;
    private static final Map<String, String> NO_HANDLES = Collections.emptyMap();
    @SuppressLint("StaticFieldLeak")
    private static BlockedChannelManager sInstance;
    private final Context mContext;
    private AiSListManager.HandleLookup mLookup;
    // Video id -> its channels' ids or nulls, the uploader's first: one per channel of a collaboration. Access order, so the least
    // recently seen video goes first.
    private final Map<String, List<String>> mChannelIdsByVideoId = new LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<String>> eldest) {
            return size() > MAX_VIDEOS;
        }
    };
    private final Map<String, Observable<Map<String, String>>> mRunningLookups = new ConcurrentHashMap<>(); // see lookUp

    private BlockedChannelManager(Context context) {
        mContext = context;
        mLookup = YouTubeServiceManager.instance().getMediaItemService()::getChannelHandlesObserve;
    }

    /**
     * Synchronized: the groups are resolved off the main thread (see HiddenVideoResolver) and built on it (see VideoGroup)
     */
    public static synchronized BlockedChannelManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new BlockedChannelManager(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * The cards without a channel id are looked up: a channel is blocked with its id
     */
    public boolean isEnabled() {
        return getData().hasChannelIds();
    }

    /**
     * A video card without its channel's id. Not a playlist (its video id is the first video's), a show, a channel or a chapter.
     */
    public static boolean isCandidate(Video video) {
        return VideoGroup.isHideableCard(video) && video.getAuthor() != null && !BlockedChannelData.isChannelId(video.channelId);
    }

    /**
     * A video card that might be a collaboration's ("X and Y"), with its uploader's id or without (search has it, Home doesn't):
     * its other channels are found by its video too
     */
    public static boolean isCollaborationCandidate(Video video) {
        return VideoGroup.isHideableCard(video) && CollaborationManager.isCandidate(video.getAuthor());
    }

    /**
     * Holds until the cards that would be hidden are known by their video (see HiddenVideoResolver), once the channel ids
     * of their names are (see AiSListManager.resolveHandles). Completes once every lookup is done, the failed ones too.
     */
    public Observable<Boolean> resolve(Collection<Video> videos) {
        BlockedChannelData data = getData();
        Set<String> videoIds = new LinkedHashSet<>();

        for (Video video : videos) {
            if ((isCandidate(video) || isCollaborationCandidate(video)) && !isFound(video.videoId) && mayBeBlocked(data, video)) {
                videoIds.add(video.videoId);
            }
        }

        if (videoIds.isEmpty()) {
            return Observable.just(true);
        }

        return Observable.fromIterable(videoIds)
                .flatMap(this::lookUp, MAX_PARALLEL_LOOKUPS)
                .toList()
                .map(found -> true)
                .toObservable();
    }

    /**
     * By the channel id of the card or the one its video was found to have (see resolve), otherwise by its name.
     * Without either id, also by the id its name was found to have: the channel was renamed since it was blocked.
     */
    public boolean isBlocked(Video video) {
        BlockedChannelData data = getData();

        if (video == null || data.isEmpty()) {
            return false;
        }

        String channelId = getChannelId(video);

        if (data.containsChannel(channelId, video.getAuthor())) {
            return true;
        }

        if (!BlockedChannelData.isChannelId(channelId) && isCandidate(video) && data.containsChannel(getNameChannelId(video), null)) {
            return true;
        }

        for (String collaboratorId : getCollaboratorIds(video)) {
            if (data.containsChannel(collaboratorId, null)) {
                return true;
            }
        }

        return false;
    }

    /**
     * By its id when it's known. A card without it (e.g. of Home) is blocked by the name right away, then gets the id
     * once its video is looked up.
     */
    public void block(Video video) {
        String channelName = video.getAuthor();
        String channelId = getChannelId(video);

        getData().addChannel(channelId, channelName);

        if (BlockedChannelData.isChannelId(channelId) || !isCandidate(video)) {
            return;
        }

        RxHelper.execute(lookUp(video.videoId), handles -> getData().setChannelId(channelName, getChannelId(video)),
                error -> Log.e(TAG, "Can't block %s by its id: %s", channelName, error.getMessage()));
    }

    /**
     * Every channel that blocks the card goes (see isBlocked)
     */
    public void unblock(Video video) {
        String channelId = getChannelId(video);

        getData().removeChannel(channelId, video.getAuthor());

        if (!BlockedChannelData.isChannelId(channelId) && isCandidate(video)) {
            getData().removeChannel(getNameChannelId(video), null);
        }

        for (String collaboratorId : getCollaboratorIds(video)) {
            getData().removeChannel(collaboratorId, null);
        }
    }

    /**
     * The card's channel id, or the one its video was found to have (see resolve), otherwise what the card has: none,
     * or a playlist page's ("VL...")
     */
    public String getChannelId(Video video) {
        if (BlockedChannelData.isChannelId(video.channelId) || !isCandidate(video)) {
            return video.channelId;
        }

        List<String> found;

        synchronized (mChannelIdsByVideoId) {
            found = mChannelIdsByVideoId.get(video.videoId);
        }

        // The uploader's
        String channelId = found != null && !found.isEmpty() ? found.get(0) : null;

        return BlockedChannelData.isChannelId(channelId) ? channelId : video.channelId;
    }

    /**
     * The ids of a collaboration's channels (a card of "X and Y"): the ones its video was found to have (see resolve), otherwise
     * the ones its name was (see AiSListManager.resolveHandles). Empty for any other card.
     */
    private List<String> getCollaboratorIds(Video video) {
        if (!isCollaborationCandidate(video)) {
            return Collections.emptyList();
        }

        List<String> found;

        synchronized (mChannelIdsByVideoId) {
            found = mChannelIdsByVideoId.get(video.videoId);
        }

        List<String> result = new ArrayList<>();

        // Its lookup failed: by the name, as a card of one channel is
        for (String channelId : found != null && !found.isEmpty() ? found : getNameChannelIds(video)) {
            if (BlockedChannelData.isChannelId(channelId)) {
                result.add(channelId);
            }
        }

        return result;
    }

    /**
     * A channel blocked with its id that the card might be of: by its name or the id found for it (see BlockedChannelData.mayContainChannel),
     * or a collaboration's by the id found for any of its channels. Its video tells.
     */
    private boolean mayBeBlocked(BlockedChannelData data, Video video) {
        if (data.mayContainChannel(getNameChannelId(video), video.getAuthor())) {
            return true;
        }

        if (!CollaborationManager.isCandidate(video.getAuthor())) {
            return false;
        }

        for (String channelId : getNameChannelIds(video)) {
            if (data.mayContainChannel(channelId, null)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The id found for the card's channel name (see AiSListManager.resolveHandles), maybe another channel's of the same name
     */
    private String getNameChannelId(Video video) {
        return AiSListManager.instance(mContext).getCachedChannelId(AiSListManager.getLookupKey(video));
    }

    /**
     * The ids found for the card's channel name: one per channel of a collaboration (see AiSListManager.resolveHandles)
     */
    private List<String> getNameChannelIds(Video video) {
        return AiSListManager.instance(mContext).getCachedChannelIds(AiSListManager.getLookupKey(video));
    }

    private boolean isFound(String videoId) {
        synchronized (mChannelIdsByVideoId) {
            return mChannelIdsByVideoId.containsKey(videoId);
        }
    }

    /**
     * One lookup per video at a time, shared by the groups and the block that need it
     */
    private Observable<Map<String, String>> lookUp(String videoId) {
        return mRunningLookups.computeIfAbsent(videoId, key -> mLookup.getChannelHandles(videoId)
                .onErrorReturn(error -> {
                    Log.e(TAG, "Can't find the channel of %s: %s", videoId, error.getMessage());
                    return NO_HANDLES;
                })
                .doOnNext(handles -> {
                    // The uploader first. Not found either: not looked up again for the rows (see resolve).
                    synchronized (mChannelIdsByVideoId) {
                        mChannelIdsByVideoId.put(videoId, new ArrayList<>(handles.values()));
                    }
                })
                .doFinally(() -> mRunningLookups.remove(videoId))
                .cache());
    }

    private BlockedChannelData getData() {
        return BlockedChannelData.instance(mContext);
    }

    /**
     * Looks up with the given one from now on and forgets the videos
     */
    void resetForTesting(AiSListManager.HandleLookup lookup) {
        mLookup = lookup;
        mRunningLookups.clear();

        synchronized (mChannelIdsByVideoId) {
            mChannelIdsByVideoId.clear();
        }
    }
}
