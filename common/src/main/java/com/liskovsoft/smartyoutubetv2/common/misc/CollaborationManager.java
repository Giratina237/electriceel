package com.liskovsoft.smartyoutubetv2.common.misc;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.sharedutils.helpers.FileHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.prefs.CollaborationsData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.reactivex.Observable;

/**
 * Tells a collaboration by the channel name on its card. TV shows one as a plain "X and Y", without the channels,
 * so it's taken as one when X and Y are both channel names but "X and Y" itself isn't (e.g. "Dan and Phil").<br/>
 * A name is a channel name when a channel search finds a channel called exactly that. Each name is searched once,
 * signed out (it stays out of the search history), before the cards are shown, and the answer is cached on disk.<br/>
 * A "not a channel" is searched again once it's a few days old, in the background (see {@link #RECHECK_MS}): a channel
 * days old isn't found by its name yet (e.g. "Will Tenny", made 4 days before its first collaboration), weeks later it is.<br/>
 * Only the English "and" is known: in another app language no collaboration is found.
 */
public class CollaborationManager {
    private static final String TAG = CollaborationManager.class.getSimpleName();
    private static final String SEPARATOR = " and ";
    private static final String NAMES_FILE = "collaborations/channel_names.tsv";
    private static final String CHANNEL = "1";
    private static final String NOT_CHANNEL = "0";
    private static final int MAX_NAMES = 5_000;
    private static final long SAVE_DELAY_MS = 10_000;
    private static final int MAX_PARALLEL_SEARCHES = 4;
    /**
     * A "not a channel" older than that is searched again. A collaboration is in the feeds for a week or two:
     * a channel missed at its first card still gets its mark on the later ones.
     */
    static final long RECHECK_MS = 3 * 24 * 60 * 60 * 1_000L;
    @SuppressLint("StaticFieldLeak")
    private static CollaborationManager sInstance;
    private final Context mContext;
    private final File mNamesFile;
    private ChannelSearch mChannelSearch;
    private Clock mClock = System::currentTimeMillis;
    // Name key -> what the search told. Access order, so the names unused the longest are dropped first.
    private final Map<String, Answer> mAnswers = Collections.synchronizedMap(new LinkedHashMap<String, Answer>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Answer> eldest) {
            return size() > MAX_NAMES;
        }
    });
    // The search failed, don't repeat it this session
    private final Set<String> mFailedNames = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, Observable<Boolean>> mRunningSearches = new ConcurrentHashMap<>();
    // Key -> the name, of the answers waiting to be searched again (see #recheck). Guards mIsRechecking too.
    private final Map<String, String> mRecheckNames = new LinkedHashMap<>();
    private boolean mIsRechecking;
    private final Runnable mSaveNames = () -> RxHelper.runAsync(this::saveNames);

    interface Clock {
        long now();
    }

    /**
     * What a channel search told about a name, and when
     */
    static final class Answer {
        final boolean isChannel;
        final long checkedMs;

        Answer(boolean isChannel, long checkedMs) {
            this.isChannel = isChannel;
            this.checkedMs = checkedMs;
        }

        /**
         * A "not a channel" to search again. A channel stays one. The time is in the future when the clock was wrong then or now:
         * searched again too, rather than kept until then.
         */
        boolean isStale(long nowMs) {
            return !isChannel && (nowMs - checkedMs >= RECHECK_MS || nowMs < checkedMs);
        }
    }

    interface ChannelSearch {
        /**
         * @return the names of the channels found, an error when the search failed
         */
        Observable<List<String>> getChannelNames(String query);
    }

    interface NameCheck {
        /**
         * @return null while it isn't known
         */
        Boolean isChannelName(String name);
    }

    /**
     * What the names known so far tell about a card
     */
    static final class Verdict {
        static final Verdict NO = new Verdict(false, null);
        static final Verdict YES = new Verdict(true, null);
        final Boolean isCollaboration; // null while undecided
        final String neededName; // the name to look up next while undecided

        private Verdict(Boolean isCollaboration, String neededName) {
            this.isCollaboration = isCollaboration;
            this.neededName = neededName;
        }

        static Verdict need(String name) {
            return new Verdict(null, name);
        }
    }

    private CollaborationManager(Context context) {
        mContext = context;
        ContentService contentService = YouTubeServiceManager.instance().getContentService();
        mChannelSearch = contentService::getChannelNamesObserve;
        mNamesFile = new File(FileHelpers.getFilesDir(context), NAMES_FILE);
        RxHelper.runAsync(this::restoreNames);
    }

    public static CollaborationManager instance(Context context) {
        if (sInstance == null) {
            sInstance = new CollaborationManager(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * Collaborations are looked for in the section (MediaGroup.TYPE_*)
     */
    public boolean isEnabled(int sectionId) {
        return getData().isEnabled(sectionId);
    }

    public boolean isHidden(int sectionId) {
        return getData().isHidingEnabled(sectionId);
    }

    public boolean isMarked(int sectionId) {
        return getData().isMarkingEnabled(sectionId);
    }

    /**
     * Not kept, like in OldVideoFilter: after CollaborationsData.resetInstanceForTesting a kept one would be stale
     */
    private CollaborationsData getData() {
        return CollaborationsData.instance(mContext);
    }

    /**
     * The channel name on the card might be a collaboration's. Others are never looked up.
     */
    public static boolean isCandidate(String author) {
        return author != null && author.contains(SEPARATOR);
    }

    /**
     * The sides of each "and" of the channel name on the card, the names its channels might have
     * (e.g. "A", "B and C", "A and B" and "C" for "A and B and C")
     */
    public static List<String> getChannelNames(String author) {
        List<String> names = new ArrayList<>();

        if (!isCandidate(author)) {
            return names;
        }

        for (int index = author.indexOf(SEPARATOR); index != -1; index = author.indexOf(SEPARATOR, index + 1)) {
            String first = author.substring(0, index).trim();
            String second = author.substring(index + SEPARATOR.length()).trim();

            if (!first.isEmpty()) {
                names.add(first);
            }

            if (!second.isEmpty()) {
                names.add(second);
            }
        }

        return names;
    }

    /**
     * @return null while a name it needs hasn't been looked up
     */
    public Boolean getCachedResult(String author) {
        return judge(author, this::getKnown).isCollaboration;
    }

    /**
     * Looks up the names the cards need, a few at a time, before they're shown (see HiddenVideoResolver).
     * Every name a card might need is searched at once, its whole name and each side of each "and", rather than one after
     * the other: a search takes about 0.4 s on a Shield. A name the answer doesn't need costs a search, kept like the others
     * (e.g. the sides of "Dan and Phil", one channel).<br/>
     * The stale answers a card's verdict read are searched again in the background, the card is shown with them (see {@link #recheck}).<br/>
     * Completes once every card is answered or its search failed.
     */
    public Observable<Boolean> resolve(Collection<String> authors) {
        Map<String, String> names = new LinkedHashMap<>(); // key -> the name searched
        Map<String, String> staleNames = new LinkedHashMap<>(); // key -> the name searched again
        long nowMs = mClock.now();

        for (String author : authors) {
            if (!needsSearch(judge(author, name -> getKnown(name, nowMs, staleNames)))) {
                continue;
            }

            for (String name : getSearchNames(author)) {
                String key = getKey(name);

                if (getKnown(name) == null && !mFailedNames.contains(key)) {
                    names.putIfAbsent(key, name);
                }
            }
        }

        Observable<Boolean> lookups = names.isEmpty() ? Observable.just(true) : Observable.fromIterable(names.values())
                .flatMap(this::search, MAX_PARALLEL_SEARCHES)
                .toList()
                .map(results -> true)
                .toObservable();

        // After the searches the cards wait for
        return lookups.doFinally(() -> recheck(staleNames));
    }

    /**
     * Searches the names again in the background, one at a time across the loads, after the searches of the cards shown:
     * the rows being filled need the pool more (see RxHelper). The cards keep the answers they had, the next load gets the new ones.
     */
    private void recheck(Map<String, String> names) {
        if (names.isEmpty()) {
            return;
        }

        synchronized (mRecheckNames) {
            for (Map.Entry<String, String> entry : names.entrySet()) {
                mRecheckNames.putIfAbsent(entry.getKey(), entry.getValue());
            }

            if (mIsRechecking) {
                return;
            }

            mIsRechecking = true;
        }

        recheckNext();
    }

    private void recheckNext() {
        String name = null;

        synchronized (mRecheckNames) {
            Iterator<Map.Entry<String, String>> iterator = mRecheckNames.entrySet().iterator();

            while (name == null && iterator.hasNext()) {
                Map.Entry<String, String> entry = iterator.next();
                iterator.remove();
                Answer answer = mAnswers.get(entry.getKey());

                // Searched since it was queued, dropped, or its search failed this session
                if (answer != null && answer.isStale(mClock.now()) && !mFailedNames.contains(entry.getKey())) {
                    name = entry.getValue();
                }
            }

            if (name == null) {
                mIsRechecking = false;
                return;
            }
        }

        // The search returns its error as a result: the queue goes on either way
        RxHelper.execute(search(name), null, error -> recheckNext(), this::recheckNext);
    }

    /**
     * The names the answer for the card might need (see {@link #judge}): the whole name, then each side of each "and"
     */
    private static List<String> getSearchNames(String author) {
        List<String> names = new ArrayList<>();
        names.add(author.trim());
        names.addAll(getChannelNames(author));
        return names;
    }

    /**
     * The same channel name, without case (see {@link #getKey})
     */
    static boolean isSameName(String name, String other) {
        return name != null && other != null && getKey(name).equals(getKey(other));
    }

    /**
     * The other side of the "and" that has the channel on one side, e.g. "Jesser" for "Sidemen and Jesser" and "Sidemen"
     * @return null when no side is that channel
     */
    static String getOtherChannelName(String author, String channelName) {
        if (!isCandidate(author) || channelName == null) {
            return null;
        }

        for (int index = author.indexOf(SEPARATOR); index != -1; index = author.indexOf(SEPARATOR, index + 1)) {
            String first = author.substring(0, index).trim();
            String second = author.substring(index + SEPARATOR.length()).trim();

            if (first.isEmpty() || second.isEmpty()) {
                continue;
            }

            if (isSameName(first, channelName)) {
                return second;
            }

            if (isSameName(second, channelName)) {
                return first;
            }
        }

        return null;
    }

    private boolean needsSearch(Verdict verdict) {
        return verdict.isCollaboration == null && !mFailedNames.contains(getKey(verdict.neededName));
    }

    /**
     * One search per name at a time, shared by the cards that need it. A failed one isn't repeated this session.
     */
    private Observable<Boolean> search(String name) {
        String key = getKey(name);

        return mRunningSearches.computeIfAbsent(key, unused -> mChannelSearch.getChannelNames(name)
                .map(names -> {
                    mAnswers.put(key, new Answer(containsName(names, name), mClock.now()));
                    Utils.postDelayed(mSaveNames, SAVE_DELAY_MS);
                    return true;
                })
                .onErrorReturn(error -> {
                    Log.e(TAG, "Can't search for the channel %s: %s", name, error.getMessage());
                    mFailedNames.add(key); // a stale answer keeps its old one
                    return true;
                })
                .doFinally(() -> mRunningSearches.remove(key))
                .cache());
    }

    private Boolean getKnown(String name) {
        Answer answer = mAnswers.get(getKey(name));
        return answer != null ? answer.isChannel : null;
    }

    /**
     * As {@link #getKnown(String)}, collects the stale answer to search again
     */
    private Boolean getKnown(String name, long nowMs, Map<String, String> staleNames) {
        String key = getKey(name);
        Answer answer = mAnswers.get(key);

        if (answer != null && answer.isStale(nowMs) && !mFailedNames.contains(key)) {
            staleNames.putIfAbsent(key, name);
        }

        return answer != null ? answer.isChannel : null;
    }

    /**
     * The rule, over the names known so far: "X and Y" is a collaboration when it isn't a channel name
     * itself and both sides of one of its "and"s are
     * @return the answer or, while undecided, the name to look up next
     */
    static Verdict judge(String author, NameCheck check) {
        if (!isCandidate(author)) {
            return Verdict.NO;
        }

        String whole = author.trim();
        Boolean isChannel = check.isChannelName(whole);

        if (isChannel == null) {
            return Verdict.need(whole);
        }

        if (isChannel) {
            return Verdict.NO; // one channel with "and" in its name
        }

        // Each "and", in case a collaborator's own name has one
        for (int index = author.indexOf(SEPARATOR); index != -1; index = author.indexOf(SEPARATOR, index + 1)) {
            String first = author.substring(0, index).trim();
            String second = author.substring(index + SEPARATOR.length()).trim();

            if (first.isEmpty() || second.isEmpty()) {
                continue;
            }

            Boolean isFirstChannel = check.isChannelName(first);

            if (isFirstChannel == null) {
                return Verdict.need(first);
            }

            if (!isFirstChannel) {
                continue;
            }

            Boolean isSecondChannel = check.isChannelName(second);

            if (isSecondChannel == null) {
                return Verdict.need(second);
            }

            if (isSecondChannel) {
                return Verdict.YES;
            }
        }

        return Verdict.NO;
    }

    /**
     * A found channel is called exactly that, without case. The rest are only like it (e.g. "Jesser Gaming" for "Jesser").
     */
    static boolean containsName(List<String> names, String name) {
        String key = getKey(name);

        for (String found : names) {
            if (key.equals(getKey(found))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Without case, and the spaces around. YouTube might put a no-break space inside.
     */
    static String getKey(String name) {
        return name != null ? name.replace(' ', ' ').trim().toLowerCase(Locale.ROOT) : null;
    }

    /**
     * Searches with the given one from now on and forgets every name
     */
    void resetForTesting(ChannelSearch search) {
        resetForTesting(search, System::currentTimeMillis);
    }

    /**
     * As {@link #resetForTesting(ChannelSearch)}, the answers are timed by the given clock
     */
    void resetForTesting(ChannelSearch search, Clock clock) {
        mChannelSearch = search;
        mClock = clock;
        mAnswers.clear();
        mFailedNames.clear();
        mRunningSearches.clear();

        synchronized (mRecheckNames) {
            mRecheckNames.clear();
            mIsRechecking = false;
        }
    }

    private void restoreNames() {
        String content = FileHelpers.getFileContents(mNamesFile);

        if (content != null) {
            readNames(content, mAnswers);
        }
    }

    private void saveNames() {
        String content;

        synchronized (mAnswers) {
            content = writeNames(mAnswers);
        }

        File parent = mNamesFile.getParentFile();

        if (parent != null && (parent.exists() || parent.mkdirs())) {
            FileHelpers.stringToFile(content, mNamesFile);
        }
    }

    /**
     * Lines of the name key, 1 or 0 and the time of the search (see {@link #writeNames}). A line without the time was saved
     * before the answers had one: as old as can be, so its "not a channel" is searched again.<br/>
     * A search done while the file was read is newer: an answer already there is kept.
     */
    static void readNames(String content, Map<String, Answer> answers) {
        for (String line : content.split("\n")) {
            String[] fields = line.split("\t");

            if ((fields.length != 2 && fields.length != 3) || fields[0].isEmpty() || !(CHANNEL.equals(fields[1]) || NOT_CHANNEL.equals(fields[1]))) {
                continue;
            }

            answers.putIfAbsent(fields[0], new Answer(CHANNEL.equals(fields[1]), fields.length == 3 ? parseTime(fields[2]) : 0));
        }
    }

    /**
     * In the order of the map, so the least recently used stay first. The caller holds its lock.
     */
    static String writeNames(Map<String, Answer> answers) {
        StringBuilder content = new StringBuilder();

        for (Map.Entry<String, Answer> entry : answers.entrySet()) {
            if (entry.getKey().contains("\t") || entry.getKey().contains("\n")) {
                continue;
            }

            Answer answer = entry.getValue();
            content.append(entry.getKey()).append('\t').append(answer.isChannel ? CHANNEL : NOT_CHANNEL)
                    .append('\t').append(answer.checkedMs).append('\n');
        }

        return content.toString();
    }

    /**
     * @return 0 for a broken time: its "not a channel" is searched again
     */
    private static long parseTime(String time) {
        try {
            return Long.parseLong(time);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
