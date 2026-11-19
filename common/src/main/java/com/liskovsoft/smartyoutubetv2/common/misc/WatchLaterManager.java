package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.prefs.WatchLaterData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.subjects.BehaviorSubject;

/**
 * Knows the videos in the signed-in account's Watch later, to mark or hide them in the sections picked in the
 * Watch later setting (see VideoGroup).<br/>
 * The whole playlist is read (15 videos a request) before the first cards that need it are shown (see HiddenVideoResolver),
 * for {@link #MAX_HOLD_MS} at most.
 * It's kept on disk, so a start doesn't wait for it, and read again in the background once it's {@link #REFRESH_PERIOD_MS} old.<br/>
 * Videos added or removed in the app count right away (see {@link #onPlaylistEdited}), and it's read again at once
 * when YouTube changed it (see {@link #reload}).<br/>
 * The cards already shown follow each change, in every row and on every screen that has the video (see {@link OnChanged}).
 */
public class WatchLaterManager {
    private static final String TAG = WatchLaterManager.class.getSimpleName();
    public static final String WATCH_LATER_PLAYLIST_ID = "WL";
    public static final String WATCH_LATER_CHANNEL_ID = "VLWL"; // the playlist opened as a channel
    private static final String IDS_FILE = "watchlater/video_ids.txt";
    private static final long REFRESH_PERIOD_MS = 15 * 60 * 1_000;
    private static final long RETRY_PERIOD_MS = 60 * 1_000;
    private static final long SAVE_DELAY_MS = 10_000;
    // A long list (5000 videos at most) takes a minute to read. The cards aren't held that long.
    private static final long MAX_HOLD_MS = 10_000;
    @SuppressLint("StaticFieldLeak")
    private static WatchLaterManager sInstance;
    private static OnChanged sOnChanged;
    private final Context mContext;
    private final File mIdsFile;
    private Loader mLoader;
    private AccountSource mAccountSource;
    private volatile Snapshot mSnapshot; // null while no list is known
    private final Map<String, Observable<Boolean>> mRunningLoads = new ConcurrentHashMap<>(); // by account
    private final Map<String, Long> mLoadStartTimes = new ConcurrentHashMap<>(); // by account
    // Added (true) or removed while a load runs: the list it reads might be older
    private final Map<String, Boolean> mPendingEdits = new ConcurrentHashMap<>();
    // The accounts whose running load might read the list as it was before YouTube changed it (see reload)
    private final Set<String> mStaleLoads = ConcurrentHashMap.newKeySet();
    private String mFailedAccount;
    private long mFailedTimeMs;
    // The list saved on disk is known
    private final BehaviorSubject<Boolean> mRestored = BehaviorSubject.create();
    private final Runnable mSaveIds = () -> RxHelper.runAsync(this::saveIds);

    interface Loader {
        /**
         * @return every video id in Watch later, an error when it can't be read
         */
        Observable<List<String>> getVideoIds();
    }

    public interface OnChanged {
        /**
         * Videos went in Watch later or out of it: edited in the app, or found when the list is read again (e.g. edited on
         * another device). On the main thread.
         *
         * @param changes by video id: it's in Watch later now (true) or not (false)
         */
        void onChanged(Map<String, Boolean> changes);
    }

    interface AccountSource {
        /**
         * @return the signed-in account or null
         */
        String getAccount();
    }

    /**
     * The list of one account
     */
    private static final class Snapshot {
        final String account;
        final Set<String> videoIds;
        final long loadedTimeMs;

        Snapshot(String account, Set<String> videoIds, long loadedTimeMs) {
            this.account = account;
            this.videoIds = videoIds;
            this.loadedTimeMs = loadedTimeMs;
        }
    }

    private WatchLaterManager(Context context) {
        mContext = context;
        mLoader = YouTubeServiceManager.instance().getContentService()::getWatchLaterVideoIdsObserve;
        mAccountSource = () -> getAccountKey(MediaServiceManager.instance().getSelectedAccount());
        mIdsFile = new File(FileHelpers.getFilesDir(context), IDS_FILE);
        RxHelper.runAsync(this::restoreIds);
    }

    public static WatchLaterManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new WatchLaterManager(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * Watch later videos are looked for in the section (MediaGroup.TYPE_*)
     */
    public boolean isEnabled(int sectionId) {
        return getData().isEnabled(sectionId);
    }

    public boolean isHidden(int sectionId) {
        return getData().isHidingEnabled(sectionId);
    }

    /**
     * Not kept, like in OldVideoFilter: after WatchLaterData.resetInstanceForTesting a kept one would be stale
     */
    private WatchLaterData getData() {
        return WatchLaterData.instance(mContext);
    }

    /**
     * The video is in the signed-in account's Watch later, as far as it's known
     */
    public boolean contains(String videoId) {
        Snapshot snapshot = mSnapshot;

        return videoId != null && snapshot != null && snapshot.videoIds.contains(videoId) && snapshot.account.equals(mAccountSource.getAccount());
    }

    /**
     * For the menus: like {@link #contains}, but only while the setting marks or hides them, since the list is kept fresh only then
     */
    public boolean isInWatchLater(String videoId) {
        return getData().getMode() != WatchLaterData.MODE_SHOW && contains(videoId);
    }

    /**
     * The cards show the Watch later playlist itself, where every video is in it (e.g. its row in Playlists or in Home)
     */
    public static boolean isWatchLaterPlaylist(VideoGroup group, Video video) {
        MediaGroup mediaGroup = group != null ? group.getMediaGroup() : null;

        // A playlist is opened as the channel "VL" + its id. Its cards play in it.
        return (mediaGroup != null && WATCH_LATER_CHANNEL_ID.equals(mediaGroup.getChannelId())) || WATCH_LATER_PLAYLIST_ID.equals(video.playlistId);
    }

    /**
     * Reads the list of the signed-in account before the cards are shown (see HiddenVideoResolver).
     * A known list is used as it is and read again in the background once it's old.
     * Completes once the list is known or can't be read.
     */
    public Observable<Boolean> resolve() {
        return mRestored.take(1).concatMap(restored -> resolveNow());
    }

    private Observable<Boolean> resolveNow() {
        String account = mAccountSource.getAccount();

        // Signed out: there's no Watch later
        if (account == null) {
            return Observable.just(true);
        }

        Snapshot snapshot = mSnapshot;
        long now = System.currentTimeMillis();

        if (snapshot != null && account.equals(snapshot.account)) {
            if (now - snapshot.loadedTimeMs >= REFRESH_PERIOD_MS && !isRetryPending(account, now)) {
                RxHelper.execute(load(account));
            }

            return Observable.just(true);
        }

        // Show the cards rather than wait again for a list that just failed
        if (isRetryPending(account, now)) {
            return Observable.just(true);
        }

        return hold(account);
    }

    /**
     * Waits for the list for {@link #MAX_HOLD_MS} after its load started, the same end for every group that waits.
     * The cards are then shown, and the list applies to the next ones once it's read.
     */
    private Observable<Boolean> hold(String account) {
        Observable<Boolean> load = load(account);
        Long startTimeMs = mLoadStartTimes.get(account);
        long leftMs = startTimeMs != null ? MAX_HOLD_MS - (System.currentTimeMillis() - startTimeMs) : MAX_HOLD_MS;

        if (leftMs <= 0) {
            return Observable.just(true);
        }

        // The load goes on after the wait ends (see load)
        return load.timeout(leftMs, TimeUnit.MILLISECONDS, AndroidSchedulers.mainThread(), Observable.just(true));
    }

    private boolean isRetryPending(String account, long now) {
        return account.equals(mFailedAccount) && now - mFailedTimeMs < RETRY_PERIOD_MS;
    }

    /**
     * One load per account at a time, shared by the groups that need it. It ends even when nobody waits for it anymore (cache).
     */
    private Observable<Boolean> load(String account) {
        return mRunningLoads.computeIfAbsent(account, key -> {
            mPendingEdits.clear();
            mStaleLoads.remove(account);
            mLoadStartTimes.put(account, System.currentTimeMillis());

            return mLoader.getVideoIds()
                    .map(videoIds -> {
                        // Read again once it ends (see doFinally)
                        if (!mStaleLoads.contains(account)) {
                            apply(account, videoIds);
                        }

                        return true;
                    })
                    .onErrorReturn(error -> {
                        Log.e(TAG, "Can't read Watch later: %s", error.getMessage());
                        mFailedAccount = account;
                        mFailedTimeMs = System.currentTimeMillis();
                        return true;
                    })
                    .doFinally(() -> {
                        mRunningLoads.remove(account);
                        mLoadStartTimes.remove(account);

                        // Not for an account left meanwhile: the list read would be the one picked now's (see apply)
                        if (mStaleLoads.contains(account) && account.equals(mAccountSource.getAccount())) {
                            RxHelper.execute(load(account));
                        }
                    })
                    .cache();
        });
    }

    /**
     * YouTube changed Watch later in a way the app can't follow video by video (e.g. it removed the watched videos):
     * it's read again now, or at its next use while the setting shows them.
     * A load already running might read it as it was, so what that one reads is dropped and it's read again after.
     */
    public void reload() {
        String account = mAccountSource.getAccount();

        if (account == null) {
            return;
        }

        // Nothing needs it while the setting shows them: it's read again at its next use (see resolveNow)
        if (getData().getMode() == WatchLaterData.MODE_SHOW) {
            Snapshot snapshot = mSnapshot;

            if (snapshot != null) {
                mSnapshot = new Snapshot(snapshot.account, snapshot.videoIds, 0);
                Utils.postDelayed(mSaveIds, SAVE_DELAY_MS);
            }

            return;
        }

        mStaleLoads.add(account);
        RxHelper.execute(load(account));
    }

    private void apply(String account, List<String> videoIds) {
        // Another account was picked while it loaded
        if (!account.equals(mAccountSource.getAccount())) {
            return;
        }

        Set<String> result = new HashSet<>(videoIds);

        for (Map.Entry<String, Boolean> edit : mPendingEdits.entrySet()) {
            if (edit.getValue()) {
                result.add(edit.getKey());
            } else {
                result.remove(edit.getKey());
            }
        }

        mPendingEdits.clear();
        Snapshot previous = mSnapshot;
        mSnapshot = new Snapshot(account, Collections.unmodifiableSet(result), System.currentTimeMillis());
        mFailedAccount = null;
        Utils.postDelayed(mSaveIds, SAVE_DELAY_MS);
        // The cards shown meanwhile were marked by the list before
        notifyChanged(getChanges(previous, account, result));
    }

    /**
     * What the list read changes for the cards already shown: the videos added and removed since the list before.
     * Without a list of the account before, each of its videos: the cards shown before it was read (e.g. while it was read
     * for longer than they were held, or when another account was picked) know none of them.
     */
    private static Map<String, Boolean> getChanges(@Nullable Snapshot previous, String account, Set<String> videoIds) {
        Map<String, Boolean> changes = new HashMap<>();

        if (previous == null || !previous.account.equals(account)) {
            for (String videoId : videoIds) {
                changes.put(videoId, true);
            }

            return changes;
        }

        for (String videoId : videoIds) {
            if (!previous.videoIds.contains(videoId)) {
                changes.put(videoId, true);
            }
        }

        for (String videoId : previous.videoIds) {
            if (!videoIds.contains(videoId)) {
                changes.put(videoId, false);
            }
        }

        return changes;
    }

    /**
     * Told about each change of Watch later, to show it on the cards at once. Called when the app starts.
     */
    public static void setOnChanged(OnChanged listener) {
        sOnChanged = listener;
    }

    private static void notifyChanged(Map<String, Boolean> changes) {
        OnChanged listener = sOnChanged;

        if (listener != null && !changes.isEmpty()) {
            listener.onChanged(changes);
        }
    }

    /**
     * A video was added to a playlist or removed from it in the app, and the change was saved
     */
    public static void onPlaylistEdited(Context context, String playlistId, Video video, boolean isAdded) {
        // No context before the app is initialized (GlobalPreferences)
        if (context == null || !WATCH_LATER_PLAYLIST_ID.equals(playlistId) || video == null || video.videoId == null) {
            return;
        }

        WatchLaterManager manager = instance(context);
        manager.onWatchLaterEdited(video.videoId, isAdded);
        // Also when the list had it already: a card shown before the list was read again doesn't
        notifyChanged(Collections.singletonMap(video.videoId, isAdded));
    }

    private void onWatchLaterEdited(String videoId, boolean isAdded) {
        if (!mRunningLoads.isEmpty()) {
            mPendingEdits.put(videoId, isAdded);
        }

        Snapshot snapshot = mSnapshot;

        if (snapshot == null || !snapshot.account.equals(mAccountSource.getAccount()) || snapshot.videoIds.contains(videoId) == isAdded) {
            return;
        }

        Set<String> videoIds = new HashSet<>(snapshot.videoIds);

        if (isAdded) {
            videoIds.add(videoId);
        } else {
            videoIds.remove(videoId);
        }

        mSnapshot = new Snapshot(snapshot.account, Collections.unmodifiableSet(videoIds), snapshot.loadedTimeMs);
        Utils.postDelayed(mSaveIds, SAVE_DELAY_MS);
    }

    /**
     * The key the list is kept by: the email or, when there's none, the name
     */
    static String getAccountKey(Account account) {
        if (account == null) {
            return null;
        }

        return account.getEmail() != null ? account.getEmail() : account.getName();
    }

    /**
     * Reads with the given ones from now on and forgets the list
     */
    void resetForTesting(Loader loader, AccountSource accountSource) {
        mLoader = loader;
        mAccountSource = accountSource;
        mSnapshot = null;
        mRunningLoads.clear();
        mLoadStartTimes.clear();
        mPendingEdits.clear();
        mStaleLoads.clear();
        mFailedAccount = null;
        mRestored.onNext(true);
    }

    /**
     * Makes the list look read the given time ago
     */
    void setLoadedTimeForTesting(long agoMs) {
        Snapshot snapshot = mSnapshot;

        if (snapshot != null) {
            mSnapshot = new Snapshot(snapshot.account, snapshot.videoIds, System.currentTimeMillis() - agoMs);
        }
    }

    private void restoreIds() {
        Snapshot restored = null;

        try {
            restored = readIds();
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't read %s: %s", mIdsFile, e.getMessage());
        }

        Snapshot result = restored;

        // Changed on the main thread only. Always emitted: the cards wait for it.
        Utils.post(() -> {
            // A list read from the network meanwhile is newer
            if (mSnapshot == null && result != null) {
                mSnapshot = result;
            }

            mRestored.onNext(true);
        });
    }

    /**
     * The file: the account, the time the list was read, then a video id per line
     */
    private Snapshot readIds() {
        String content = FileHelpers.getFileContents(mIdsFile);
        String[] lines = content != null ? content.split("\n") : new String[0];

        if (lines.length < 2 || lines[0].isEmpty()) {
            return null;
        }

        Set<String> videoIds = new HashSet<>();

        for (int i = 2; i < lines.length; i++) {
            if (!lines[i].isEmpty()) {
                videoIds.add(lines[i]);
            }
        }

        // An unreadable time makes it old: it's read again at the first use
        return new Snapshot(lines[0], Collections.unmodifiableSet(videoIds), Helpers.parseLong(lines[1], 0));
    }

    private void saveIds() {
        Snapshot snapshot = mSnapshot;

        if (snapshot == null || snapshot.account.contains("\n")) {
            return;
        }

        StringBuilder content = new StringBuilder()
                .append(snapshot.account).append('\n')
                .append(snapshot.loadedTimeMs).append('\n');

        for (String videoId : snapshot.videoIds) {
            content.append(videoId).append('\n');
        }

        File parent = mIdsFile.getParentFile();

        if (parent != null && (parent.exists() || parent.mkdirs())) {
            FileHelpers.stringToFile(content.toString(), mIdsFile);
        }
    }
}
