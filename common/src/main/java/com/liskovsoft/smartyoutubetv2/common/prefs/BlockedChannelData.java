package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.PendingSave;
import android.content.Context;
import android.util.Pair;

import androidx.annotation.NonNull;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeSet;
import java.util.Set;

/**
 * The channels are matched by the channel id, by the name only when there's no channel id to compare (see Channel#matches).<br/>
 * Read for each video of the rows, maybe off the main thread, so the channels are read and changed under the lock.
 */
public class BlockedChannelData implements ProfileChangeListener {
    static final String BLOCKED_CHANNEL_DATA = "blocked_channel_data";
    private static final String CHANNEL_ID_PREFIX = "UC";
    @SuppressLint("StaticFieldLeak")
    private static BlockedChannelData sInstance;
    private final AppPrefs mPrefs;
    // Alphabetical, as the Blocked channels section shows them. Two channels of the same name are two entries.
    private final TreeSet<Channel> mChannels = new TreeSet<>(BlockedChannelData::compare);
    private final PendingSave mPersistStateInt = new PendingSave(this::persistStateInt);
    private final List<BlockedChannelListener> mListeners = new ArrayList<>();

    private static class Channel {
        public String channelId;
        public String channelName;

        public Channel(String channelId, String channelName) {
            this.channelId = channelId;
            this.channelName = channelName;
        }

        public static Channel fromString(String specs) {
            String[] split = Helpers.splitObj(specs);

            if (split == null || split.length != 2) {
                return null;
            }

            return new Channel(Helpers.parseStr(split[0]), Helpers.parseStr(split[1]));
        }

        /**
         * By the channel id when both have one: the channel stays blocked after it's renamed, and another channel of the same name
         * isn't blocked. Otherwise by the name, ignoring case: a card without the id (e.g. of Home), a channel blocked from one, or
         * a card of a playlist, whose id is its page's ("VL...") rather than its channel's.
         */
        public boolean matches(String id, String name) {
            if (isChannelId(channelId) && isChannelId(id)) {
                return channelId.equals(id);
            }

            return channelName != null && channelName.equalsIgnoreCase(name);
        }

        @NonNull
        @Override
        public String toString() {
            return Helpers.mergeObj(channelId, channelName);
        }
    }

    public interface BlockedChannelListener {
        void onChanged();
    }

    private BlockedChannelData(Context context) {
        mPrefs = AppPrefs.instance(context);
        mPrefs.addListener(this);
        restoreState();
    }

    public static BlockedChannelData instance(Context context) {
        if (sInstance == null) {
            sInstance = new BlockedChannelData(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * Add a channel to the block list. Its entry of before goes (e.g. under the name the channel had then).
     */
    public void addChannel(String channelId, String channelName) {
        if (Helpers.allNulls(channelId, channelName)) {
            return;
        }

        synchronized (this) {
            removeMatching(channelId, channelName);
            mChannels.add(new Channel(channelId, channelName));
        }

        persistState();
        notifyListeners();
    }

    /**
     * Remove a channel from the list: every entry that blocks it (see containsChannel)
     */
    public void removeChannel(String channelId, String channelName) {
        if (Helpers.allNulls(channelId, channelName) || isEmpty()) {
            return;
        }

        synchronized (this) {
            removeMatching(channelId, channelName);
        }

        persistState();
        notifyListeners();
    }

    /**
     * Gives the channel blocked by its name alone its id, once it's found (see BlockedChannelManager.block).
     * Nothing when it's been unblocked since.
     */
    public void setChannelId(String channelName, String channelId) {
        if (channelName == null || !isChannelId(channelId)) {
            return;
        }

        synchronized (this) {
            if (!containsNameOnly(channelName)) {
                return;
            }

            removeMatching(channelId, channelName);
            mChannels.add(new Channel(channelId, channelName));
        }

        persistState();
        notifyListeners();
    }

    /**
     * Check if a channel is blocked
     */
    public synchronized boolean containsChannel(String channelId, String channelName) {
        if (Helpers.allNulls(channelId, channelName) || mChannels.isEmpty()) {
            return false;
        }

        for (Channel channel : mChannels) {
            if (channel.matches(channelId, channelName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * A channel blocked with its id that a card without one might be of: by the card's name, or by the id found for that name
     * (see BlockedChannelManager). The card's video tells which channel it is.
     */
    public synchronized boolean mayContainChannel(String channelId, String channelName) {
        for (Channel channel : mChannels) {
            if (isChannelId(channel.channelId) && (channel.matches(channelId, null) || channel.matches(null, channelName))) {
                return true;
            }
        }

        return false;
    }

    /**
     * A channel is blocked with its id: the cards that have none are looked up (see BlockedChannelManager)
     */
    public synchronized boolean hasChannelIds() {
        for (Channel channel : mChannels) {
            if (isChannelId(channel.channelId)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get all blocked channels with their names
     *
     * @return List of channelId -> channel name
     */
    public synchronized List<Pair<String, String>> getChannelIdsWithNames() {
        List<Pair<String, String>> result = new ArrayList<>();

        for (Channel item : mChannels) {
            result.add(new Pair<>(item.channelId, item.channelName));
        }

        return result;
    }

    /**
     * Get the count of blacklisted channels
     *
     * @return Number of blacklisted channels
     */
    public synchronized int getChannelCount() {
        return mChannels.size();
    }

    public synchronized boolean isEmpty() {
        return mChannels.isEmpty();
    }

    /**
     * Clear all blacklisted channels
     */
    public void clear() {
        synchronized (this) {
            mChannels.clear();
        }

        persistState();
    }

    /**
     * A channel's id ("UC..."), not a playlist page's ("VL...")
     */
    public static boolean isChannelId(String id) {
        return id != null && id.startsWith(CHANNEL_ID_PREFIX);
    }

    private void removeMatching(String channelId, String channelName) {
        mChannels.removeIf(channel -> channel.matches(channelId, channelName));
    }

    private boolean containsNameOnly(String channelName) {
        for (Channel channel : mChannels) {
            if (!isChannelId(channel.channelId) && channel.matches(null, channelName)) {
                return true;
            }
        }

        return false;
    }

    /**
     * By the name, then by the id: the entries of two channels of the same name are both kept
     */
    private static int compare(Channel channel1, Channel channel2) {
        int result = Helpers.compareToIgnoreCase(channel1.channelName, channel2.channelName);

        if (result != 0) {
            return result;
        }

        if (channel1.channelId == null || channel2.channelId == null) {
            return channel1.channelId == null ? (channel2.channelId == null ? 0 : -1) : 1; // nulls first
        }

        return channel1.channelId.compareTo(channel2.channelId);
    }

    private synchronized void restoreState() {
        String data = mPrefs.getProfileData(BLOCKED_CHANNEL_DATA);

        String[] split = Helpers.splitData(data);

        mChannels.clear();
        mChannels.addAll(Helpers.parseList(split, 0, Channel::fromString));
        // null

        restoreOldData(split);
    }

    private void restoreOldData(String[] split) {
        Map<String, String> channelIdsWithNames = Helpers.parseMap(split, 1, Helpers::parseStr, Helpers::parseStr);

        if (!channelIdsWithNames.isEmpty()) {
            for (Entry<String, String> entry : channelIdsWithNames.entrySet()) {
                if (!containsChannel(entry.getKey(), entry.getValue())) {
                    mChannels.add(new Channel(entry.getKey(), entry.getValue()));
                }
            }
        }
    }

    public void persistNow() {
        mPersistStateInt.post();
    }

    private void persistState() {
        mPersistStateInt.post();
    }

    private void persistStateInt() {
        List<Channel> channels;

        synchronized (this) {
            channels = new ArrayList<>(mChannels);
        }

        mPrefs.setProfileData(BLOCKED_CHANNEL_DATA, Helpers.mergeData(
                channels, null));
    }

    public void addListener(BlockedChannelListener listener) {
        if (!mListeners.contains(listener)) {
            mListeners.add(listener);
        }
    }

    public void removeListener(BlockedChannelListener listener) {
        mListeners.remove(listener);
    }

    private void notifyListeners() {
        for (BlockedChannelListener listener : mListeners) {
            listener.onChanged();
        }
    }

    @Override
    public void onProfileChanged() {
        restoreState();
    }

    @Override
    public void persistPendingData() {
        mPersistStateInt.flush();
    }

    @Override
    public void onProfileDataSynced(Set<String> keys) {
        if (keys.contains(BLOCKED_CHANNEL_DATA)) {
            onProfileChanged();
        }
    }

    /**
     * The next instance reads the saved values again
     */
    static void resetInstanceForTesting() {
        sInstance = null;
    }
}
