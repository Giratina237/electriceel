package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.prefs.MembersData;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;

/**
 * Reads the channels the signed-in account is a member of, for the Member videos setting: any membership shows the members' videos and
 * sets the setting to Show (see MembersData.onMembershipsRead).<br/>
 * Read once per account in a run, while the setting hides them, before the first cards with one are shown (see HiddenVideoResolver),
 * for {@link #MAX_HOLD_MS} at most. A read that failed changes nothing: the cards are shown as the setting is, and it's read again for
 * later ones after {@link #RETRY_PERIOD_MS}.
 */
public class MembershipManager {
    private static final String TAG = MembershipManager.class.getSimpleName();
    private static final long RETRY_PERIOD_MS = 60 * 1_000;
    // A single request (the TV guide): the cards aren't held longer than a slow one takes
    private static final long MAX_HOLD_MS = 5_000;
    @SuppressLint("StaticFieldLeak")
    private static MembershipManager sInstance;
    private final Context mContext;
    private Loader mLoader;
    private AccountSource mAccountSource;
    private final Set<String> mReadAccounts = ConcurrentHashMap.newKeySet();
    private final Map<String, Observable<Boolean>> mRunningReads = new ConcurrentHashMap<>(); // by account
    private final Map<String, Long> mReadStartTimes = new ConcurrentHashMap<>(); // by account
    private String mFailedAccount;
    private long mFailedTimeMs;

    interface Loader {
        /**
         * @return the ids of the channels the account is a member of, empty for none, an error when they can't be read
         */
        Observable<List<String>> getChannelIds();
    }

    interface AccountSource {
        /**
         * @return the signed-in account or null
         */
        @Nullable
        String getAccount();
    }

    private MembershipManager(Context context) {
        mContext = context;
        mLoader = YouTubeServiceManager.instance().getContentService()::getMembershipChannelIdsObserve;
        mAccountSource = () -> WatchLaterManager.getAccountKey(MediaServiceManager.instance().getSelectedAccount());
    }

    public static MembershipManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new MembershipManager(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * Not kept: after MembersData.clearForTesting a kept one would be stale
     */
    private MembersData getData() {
        return MembersData.instance(mContext);
    }

    /**
     * The cards of the groups wait for the read (see {@link #resolve}): one only the channel's members watch is among them, and the
     * setting hides them
     */
    public boolean isNeededFor(List<MediaGroup> mediaGroups) {
        return hasMembersVideo(mediaGroups) && getData().getMode() == MembersData.MODE_HIDE;
    }

    private static boolean hasMembersVideo(List<MediaGroup> mediaGroups) {
        for (MediaGroup mediaGroup : mediaGroups) {
            List<MediaItem> items = mediaGroup != null ? mediaGroup.getMediaItems() : null;

            if (items == null) {
                continue;
            }

            for (MediaItem item : items) {
                if (item != null && item.getMembersLabel() != null) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Reads the memberships of the signed-in account, once in a run, before the cards are shown (see HiddenVideoResolver).
     * Completes once they're known, can't be read, or {@link #MAX_HOLD_MS} after the read started: the read goes on, and applies to the
     * cards after.
     */
    public Observable<Boolean> resolve() {
        String account = mAccountSource.getAccount();
        long now = System.currentTimeMillis();

        // Signed out: a member of nothing. Shown already, or not hidden: nothing to change.
        if (account == null || mReadAccounts.contains(account) || getData().getMode() != MembersData.MODE_HIDE) {
            return Observable.just(true);
        }

        // Show the cards rather than wait again for a read that just failed
        if (account.equals(mFailedAccount) && now - mFailedTimeMs < RETRY_PERIOD_MS) {
            return Observable.just(true);
        }

        Observable<Boolean> read = read(account);
        Long startTimeMs = mReadStartTimes.get(account);
        // Not now: a read started just above started after it
        long leftMs = startTimeMs != null ? MAX_HOLD_MS - (System.currentTimeMillis() - startTimeMs) : MAX_HOLD_MS;

        if (leftMs <= 0) {
            return Observable.just(true);
        }

        return read.timeout(leftMs, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread(), Observable.just(true));
    }

    /**
     * One read per account at a time, shared by the groups that wait for it. It ends even when nobody waits for it anymore (cache).
     */
    private Observable<Boolean> read(String account) {
        return mRunningReads.computeIfAbsent(account, key -> {
            mReadStartTimes.put(account, System.currentTimeMillis());

            return mLoader.getChannelIds()
                    .map(channelIds -> {
                        apply(account, channelIds);
                        return true;
                    })
                    .onErrorReturn(error -> {
                        Log.e(TAG, "Can't read the memberships: %s", error.getMessage());
                        mFailedAccount = account;
                        mFailedTimeMs = System.currentTimeMillis();
                        return true;
                    })
                    .doFinally(() -> {
                        mRunningReads.remove(account);
                        mReadStartTimes.remove(account);
                    })
                    .cache();
        });
    }

    private void apply(String account, List<String> channelIds) {
        // Another account was picked while they were read: the setting is that one's now
        if (!account.equals(mAccountSource.getAccount())) {
            return;
        }

        mReadAccounts.add(account);

        if (getData().onMembershipsRead(channelIds)) {
            Log.d(TAG, "A member of %s channels: member videos are shown", channelIds.size());
        }
    }

    /**
     * Reads with the given ones from now on, and forgets what was read
     */
    @VisibleForTesting
    void resetForTesting(Loader loader, AccountSource accountSource) {
        mLoader = loader;
        mAccountSource = accountSource;
        mReadAccounts.clear();
        mRunningReads.clear();
        mReadStartTimes.clear();
        mFailedAccount = null;
        mFailedTimeMs = 0;
    }

    @VisibleForTesting
    void setFailedTimeForTesting(long agoMs) {
        mFailedTimeMs = System.currentTimeMillis() - agoMs;
    }
}
