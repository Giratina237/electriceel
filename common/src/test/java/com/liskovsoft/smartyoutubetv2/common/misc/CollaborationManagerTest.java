package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.CollaborationManager.Answer;
import com.liskovsoft.smartyoutubetv2.common.misc.CollaborationManager.Verdict;
import com.liskovsoft.smartyoutubetv2.common.prefs.CollaborationsData;
import com.liskovsoft.smartyoutubetv2.common.prefs.FilterExclusionData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CollaborationManagerTest {
    private static final String COLLABORATION = "Sidemen and Jesser";
    private static final String DUO = "Dan and Phil"; // one channel
    // What the channel search finds: similar channels too, the exact name only for real channels
    private static final Map<String, List<String>> SEARCH_RESULTS = new HashMap<>();

    static {
        SEARCH_RESULTS.put("Sidemen and Jesser", Arrays.asList("Sidemen", "Jesser", "Sidemen Reacts"));
        SEARCH_RESULTS.put("Sidemen", Arrays.asList("Sidemen", "MoreSidemen", "SidemenReacts"));
        SEARCH_RESULTS.put("Jesser", Arrays.asList("Jesser", "Team Jesser", "Jesser Gaming"));
        SEARCH_RESULTS.put("Dan and Phil", Arrays.asList("Dan and Phil", "DanAndPhilGAMES"));
        SEARCH_RESULTS.put("Dan", Arrays.asList("Dan", "Dan TDM"));
        SEARCH_RESULTS.put("Phil", Arrays.asList("Phil", "Phil Swift"));
        SEARCH_RESULTS.put("Live Aid and Friends", Collections.singletonList("Live Aid"));
        SEARCH_RESULTS.put("Live Aid", Arrays.asList("Live Aid", "LIVE AID 85"));
        SEARCH_RESULTS.put("Friends", Arrays.asList("Friends TV", "Friends Forever"));
    }

    private CollaborationManager mManager;
    private CollaborationsData mData;
    private final List<String> mSearches = new ArrayList<>();
    // What the search finds now, a test can change it (e.g. a channel too new to be found)
    private final Map<String, List<String>> mResults = new HashMap<>(SEARCH_RESULTS);
    private final Map<String, PublishSubject<List<String>>> mHeldSearches = new HashMap<>();
    private boolean mIsSearchFailing;
    private long mNowMs = 1_700_000_000_000L;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        GlobalPreferences.instance(context);
        mData = CollaborationsData.instance(context);
        mData.setMode(CollaborationsData.MODE_SHOW);
        mData.setSectionEnabled(MediaGroup.TYPE_HOME, true);
        mData.setSectionEnabled(MediaGroup.TYPE_SUBSCRIPTIONS, true);
        mData.setSectionEnabled(MediaGroup.TYPE_GAMING, false);
        mManager = CollaborationManager.instance(context);
        mManager.resetForTesting(this::search, () -> mNowMs);
    }

    @After
    public void tearDown() {
        mData.setMode(CollaborationsData.MODE_SHOW);
        getExclusions().setExcluded(FilterExclusionData.FILTER_COLLABORATIONS, "UCsidemen", "Sidemen", false);
        Utils.sHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void bothSidesMustBeChannels() {
        assertEquals(Boolean.TRUE, judge(COLLABORATION, "Sidemen", "Jesser").isCollaboration);
        assertEquals(Boolean.FALSE, judge("Live Aid and Friends", "Live Aid").isCollaboration);
        assertEquals(Boolean.FALSE, judge("Friends and Live Aid", "Live Aid").isCollaboration);
    }

    @Test
    public void oneChannelWithAndInItsNameIsNotACollaboration() {
        Map<String, Boolean> known = new HashMap<>();
        known.put(DUO, true);
        known.put("Dan", true);
        known.put("Phil", true);

        assertEquals(Boolean.FALSE, CollaborationManager.judge(DUO, known::get).isCollaboration);
    }

    @Test
    public void namesAreNeededInOrder() {
        Map<String, Boolean> known = new HashMap<>();

        assertEquals(COLLABORATION, CollaborationManager.judge(COLLABORATION, known::get).neededName);

        known.put(COLLABORATION, false);
        assertEquals("Sidemen", CollaborationManager.judge(COLLABORATION, known::get).neededName);

        known.put("Sidemen", true);
        assertEquals("Jesser", CollaborationManager.judge(COLLABORATION, known::get).neededName);

        known.put("Jesser", true);
        Verdict verdict = CollaborationManager.judge(COLLABORATION, known::get);
        assertEquals(Boolean.TRUE, verdict.isCollaboration);
        assertNull(verdict.neededName);
    }

    @Test
    public void secondSideIsSkippedWhenTheFirstIsNoChannel() {
        Map<String, Boolean> known = new HashMap<>();
        known.put("Friends and Live Aid", false);
        known.put("Friends", false);

        assertEquals(Boolean.FALSE, CollaborationManager.judge("Friends and Live Aid", known::get).isCollaboration);
    }

    @Test
    public void eachAndIsTried() {
        // A collaborator with "and" in its own name
        String author = "Rock and Roll and Jesser";
        Map<String, Boolean> known = new HashMap<>();
        known.put(author, false);

        assertEquals("Rock", CollaborationManager.judge(author, known::get).neededName);

        known.put("Rock", false);
        assertEquals("Rock and Roll", CollaborationManager.judge(author, known::get).neededName);

        known.put("Rock and Roll", true);
        known.put("Jesser", true);
        assertEquals(Boolean.TRUE, CollaborationManager.judge(author, known::get).isCollaboration);
    }

    @Test
    public void onlyTheEnglishAndIsACandidate() {
        assertTrue(CollaborationManager.isCandidate(COLLABORATION));
        assertFalse(CollaborationManager.isCandidate("Sidemen y Jesser"));
        assertFalse(CollaborationManager.isCandidate("Sidemen & Jesser"));
        assertFalse(CollaborationManager.isCandidate("Brandon Andrews"));
        assertFalse(CollaborationManager.isCandidate("Sidemen"));
        assertFalse(CollaborationManager.isCandidate(null));

        // Never looked up
        assertEquals(Boolean.FALSE, CollaborationManager.judge("Sidemen", name -> null).isCollaboration);
    }

    @Test
    public void channelNamesAreTheSidesOfEachAnd() {
        assertEquals(Arrays.asList("Sidemen", "Jesser"), CollaborationManager.getChannelNames(COLLABORATION));
        assertEquals(Arrays.asList("Rock", "Roll and Jesser", "Rock and Roll", "Jesser"),
                CollaborationManager.getChannelNames("Rock and Roll and Jesser"));
        assertTrue(CollaborationManager.getChannelNames("Sidemen").isEmpty());
        assertTrue(CollaborationManager.getChannelNames(null).isEmpty());
    }

    @Test
    public void onlyTheExactNameIsAChannel() {
        assertTrue(CollaborationManager.containsName(Arrays.asList("Team Jesser", "JESSER "), "Jesser"));
        assertTrue(CollaborationManager.containsName(Collections.singletonList("Live Aid"), "Live Aid"));
        assertFalse(CollaborationManager.containsName(Arrays.asList("Jesser Gaming", "Team Jesser"), "Jesser"));
        assertFalse(CollaborationManager.containsName(Collections.emptyList(), "Jesser"));
    }

    @Test
    public void namesAreSearchedOnce() {
        assertTrue(resolve(COLLABORATION));
        assertEquals(Arrays.asList(COLLABORATION, "Sidemen", "Jesser"), mSearches);
        assertEquals(Boolean.TRUE, mManager.getCachedResult(COLLABORATION));

        // Known now
        assertTrue(resolve(COLLABORATION));
        assertEquals(3, mSearches.size());
    }

    /**
     * The channel the uploader collaborated with (see AiSListManager)
     */
    @Test
    public void otherSideOfCollaborationIsFound() {
        assertEquals("Jesser", CollaborationManager.getOtherChannelName(COLLABORATION, "sidemen"));
        assertEquals("Sidemen", CollaborationManager.getOtherChannelName(COLLABORATION, "Jesser"));
        assertEquals("Rock and Roll", CollaborationManager.getOtherChannelName("Rock and Roll and Jesser", "Jesser"));
        assertNull(CollaborationManager.getOtherChannelName(COLLABORATION, "MoreSidemen"));
        assertNull(CollaborationManager.getOtherChannelName("Sidemen", "Sidemen"));
        assertNull(CollaborationManager.getOtherChannelName(COLLABORATION, null));
    }

    @Test
    public void namesAreSearchedAtOnce() {
        PublishSubject<List<String>> wholeSearch = PublishSubject.create(); // ends when the test says
        mManager.resetForTesting(query -> COLLABORATION.equals(query) ? wholeSearch : search(query));
        List<Boolean> done = new ArrayList<>();

        mManager.resolve(Collections.singletonList(COLLABORATION)).subscribe(done::add);

        // The sides don't wait for the whole name
        assertEquals(Arrays.asList("Sidemen", "Jesser"), mSearches);
        assertTrue(done.isEmpty());

        wholeSearch.onNext(SEARCH_RESULTS.get(COLLABORATION));
        wholeSearch.onComplete();

        assertEquals(Collections.singletonList(true), done);
        assertEquals(Boolean.TRUE, mManager.getCachedResult(COLLABORATION));
    }

    /**
     * Its sides are searched together with it: they aren't needed in the end, but they're kept
     */
    @Test
    public void duoChannelIsSearchedWithItsSides() {
        assertTrue(resolve(DUO));

        assertEquals(Arrays.asList(DUO, "Dan", "Phil"), mSearches);
        assertEquals(Boolean.FALSE, mManager.getCachedResult(DUO));
    }

    @Test
    public void namesAreSharedBetweenCards() {
        assertTrue(resolve(COLLABORATION, "Jesser and Sidemen"));

        // "Sidemen" and "Jesser" aren't searched again
        assertEquals(Arrays.asList(COLLABORATION, "Sidemen", "Jesser", "Jesser and Sidemen"), mSearches);
        assertEquals(Boolean.TRUE, mManager.getCachedResult("Jesser and Sidemen"));
    }

    @Test
    public void failedSearchIsNotRepeated() {
        mIsSearchFailing = true;

        // Completes all the same: the cards are shown
        assertTrue(resolve(COLLABORATION));
        assertTrue(resolve(COLLABORATION));

        // Each name once, all at once
        assertEquals(Arrays.asList(COLLABORATION, "Sidemen", "Jesser"), mSearches);
        assertNull(mManager.getCachedResult(COLLABORATION));
    }

    @Test
    public void runningSearchIsShared() {
        PublishSubject<List<String>> firstSearch = PublishSubject.create(); // ends when the test says
        List<String> started = new ArrayList<>();
        mManager.resetForTesting(query -> {
            started.add(query);
            return COLLABORATION.equals(query) ? firstSearch : search(query);
        });

        List<Boolean> done = new ArrayList<>();
        mManager.resolve(Collections.singletonList(COLLABORATION)).subscribe(done::add);
        mManager.resolve(Collections.singletonList(COLLABORATION)).subscribe(done::add);

        assertTrue(done.isEmpty());

        firstSearch.onNext(SEARCH_RESULTS.get(COLLABORATION));
        firstSearch.onComplete();

        assertEquals(Arrays.asList(true, true), done);
        assertEquals(Arrays.asList(COLLABORATION, "Sidemen", "Jesser"), started);
    }

    @Test
    public void knownCollaborationIsHiddenOnlyInPickedSections() {
        learn(COLLABORATION);
        mData.setMode(CollaborationsData.MODE_HIDE);

        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createVideo("collab", COLLABORATION));
        home.add(createVideo("solo", "Sidemen"));

        VideoGroup gaming = createGroup(MediaGroup.TYPE_GAMING);
        gaming.add(createVideo("collab", COLLABORATION));

        assertEquals(1, home.getSize());
        assertEquals("solo", home.get(0).videoId);
        assertEquals(1, gaming.getSize());
        assertFalse(gaming.get(0).isCollaboration);
    }

    /**
     * One of its channels is excluded from the hiding in the context menu (see FilterExclusionData): the card only has "X and Y"
     */
    @Test
    public void collaborationWithAnExcludedChannelIsMarkedInHideMode() {
        learn(COLLABORATION);
        learn("Phil and Jesser");
        mData.setMode(CollaborationsData.MODE_HIDE);
        getExclusions().setExcluded(FilterExclusionData.FILTER_COLLABORATIONS, "UCsidemen", "Sidemen", true);

        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createVideo("collab", COLLABORATION));
        home.add(createVideo("other", "Phil and Jesser"));

        assertEquals(1, home.getSize());
        assertEquals("collab", home.get(0).videoId);
        assertTrue(home.get(0).isCollaboration);
    }

    @Test
    public void knownCollaborationIsMarkedInMarkMode() {
        learn(COLLABORATION);
        learn(DUO);
        mData.setMode(CollaborationsData.MODE_MARK);

        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createVideo("collab", COLLABORATION));
        home.add(createVideo("duo", DUO));

        assertEquals(2, home.getSize());
        assertTrue(home.get(0).isCollaboration);
        assertFalse(home.get(1).isCollaboration);
    }

    @Test
    public void showModeLeavesCollaborationsAlone() {
        learn(COLLABORATION);

        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createVideo("collab", COLLABORATION));

        assertEquals(1, home.getSize());
        assertFalse(home.get(0).isCollaboration);
    }

    /**
     * The names are looked up before the group is created (see HiddenVideoResolverTest)
     */
    @Test
    public void groupOnlyReadsTheCache() {
        mData.setMode(CollaborationsData.MODE_HIDE);

        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createVideo("collab", COLLABORATION));

        assertEquals(1, home.getSize());
        assertTrue(mSearches.isEmpty()); // the group only reads the cache
    }

    /**
     * A channel days old isn't found by its name yet (e.g. "Will Tenny" in its first collaboration), later it is
     */
    @Test
    public void newChannelIsFoundOnceItsAnswerIsStale() {
        mResults.put("Jesser", Arrays.asList("Jesser Gaming", "Team Jesser"));
        learn(COLLABORATION);
        assertEquals(Boolean.FALSE, mManager.getCachedResult(COLLABORATION));
        mResults.put("Jesser", SEARCH_RESULTS.get("Jesser"));

        mNowMs += CollaborationManager.RECHECK_MS - 1;
        assertTrue(resolve(COLLABORATION));
        assertTrue(mSearches.isEmpty());

        mNowMs += 1;
        assertTrue(resolve(COLLABORATION));

        // The "not a channel" answers the verdict read
        assertEquals(Arrays.asList(COLLABORATION, "Jesser"), mSearches);
        assertEquals(Boolean.TRUE, mManager.getCachedResult(COLLABORATION));

        // Fresh again
        assertTrue(resolve(COLLABORATION));
        assertEquals(2, mSearches.size());
    }

    @Test
    public void staleAnswersAreSearchedAgainOneAtATimeAfterTheCardsAreShown() {
        mResults.put("Jesser", Collections.singletonList("Jesser Gaming"));
        learn(COLLABORATION);
        mResults.put("Jesser", SEARCH_RESULTS.get("Jesser"));
        mNowMs += CollaborationManager.RECHECK_MS;
        PublishSubject<List<String>> wholeSearch = hold(COLLABORATION);

        // The cards don't wait, a second load doesn't add a search
        assertTrue(resolve(COLLABORATION));
        assertTrue(resolve(COLLABORATION));
        assertEquals(Boolean.FALSE, mManager.getCachedResult(COLLABORATION));
        assertEquals(Collections.singletonList(COLLABORATION), mSearches);

        wholeSearch.onNext(SEARCH_RESULTS.get(COLLABORATION));
        wholeSearch.onComplete();

        assertEquals(Arrays.asList(COLLABORATION, "Jesser"), mSearches);
        assertEquals(Boolean.TRUE, mManager.getCachedResult(COLLABORATION));
    }

    @Test
    public void channelIsNotSearchedAgain() {
        learn(COLLABORATION);
        mNowMs += 10 * CollaborationManager.RECHECK_MS;

        assertTrue(resolve(COLLABORATION));

        // Only the whole name, not a channel
        assertEquals(Collections.singletonList(COLLABORATION), mSearches);
        assertEquals(Boolean.TRUE, mManager.getCachedResult(COLLABORATION));
    }

    @Test
    public void failedSearchKeepsTheStaleAnswer() {
        mResults.put("Jesser", Collections.singletonList("Jesser Gaming"));
        learn(COLLABORATION);
        mNowMs += CollaborationManager.RECHECK_MS;
        mIsSearchFailing = true;

        assertTrue(resolve(COLLABORATION));
        assertTrue(resolve(COLLABORATION));

        // Each once this session, the queue goes on after a failure
        assertEquals(Arrays.asList(COLLABORATION, "Jesser"), mSearches);
        assertEquals(Boolean.FALSE, mManager.getCachedResult(COLLABORATION));
    }

    @Test
    public void answersAreSavedWithTheirTime() {
        Map<String, Answer> answers = new LinkedHashMap<>();
        answers.put("sidemen", new Answer(true, 5));
        answers.put("jesser", new Answer(false, 7));

        String content = CollaborationManager.writeNames(answers);
        assertEquals("sidemen\t1\t5\njesser\t0\t7\n", content);

        Map<String, Answer> read = new LinkedHashMap<>();
        read.put("jesser", new Answer(true, 9)); // searched while the file was read
        CollaborationManager.readNames(content, read);

        assertEquals(Arrays.asList("jesser", "sidemen"), new ArrayList<>(read.keySet()));
        assertTrue(read.get("sidemen").isChannel);
        assertEquals(5, read.get("sidemen").checkedMs);
        assertTrue(read.get("jesser").isChannel);
        assertEquals(9, read.get("jesser").checkedMs);
    }

    /**
     * Saved before the answers had a time, or with a broken one: its "not a channel" is searched again
     */
    @Test
    public void answerWithoutItsTimeIsStale() {
        Map<String, Answer> read = new LinkedHashMap<>();
        CollaborationManager.readNames("will tenny\t0\nwill tennyson\t1\nbroken\t0\tsoon\nunknown\t2\t5\n\n", read);

        assertTrue(read.get("will tenny").isStale(mNowMs));
        assertFalse(read.get("will tennyson").isStale(mNowMs));
        assertTrue(read.get("broken").isStale(mNowMs));
        assertNull(read.get("unknown"));
    }

    @Test
    public void answerFromTheFutureIsStale() {
        assertTrue(new Answer(false, mNowMs + 1).isStale(mNowMs));
        assertFalse(new Answer(false, mNowMs).isStale(mNowMs));
        assertFalse(new Answer(true, 0).isStale(mNowMs));
    }

    /**
     * The search of the name ends when the test says
     */
    private PublishSubject<List<String>> hold(String query) {
        PublishSubject<List<String>> search = PublishSubject.create();
        mHeldSearches.put(query, search);
        return search;
    }

    private Observable<List<String>> search(String query) {
        mSearches.add(query);
        PublishSubject<List<String>> held = mHeldSearches.get(query);

        if (held != null) {
            return held;
        }

        if (mIsSearchFailing) {
            return Observable.error(new IllegalStateException("No network"));
        }

        List<String> names = mResults.get(query);

        return Observable.just(names != null ? names : Collections.emptyList());
    }

    /**
     * @return the lookups ended
     */
    private boolean resolve(String... authors) {
        List<Boolean> done = new ArrayList<>();
        mManager.resolve(Arrays.asList(authors)).subscribe(done::add);
        return done.equals(Collections.singletonList(true));
    }

    /**
     * Looks the card up, then forgets the searches made
     */
    private void learn(String author) {
        resolve(author);
        mSearches.clear();
    }

    private static Verdict judge(String author, String... channels) {
        List<String> names = Arrays.asList(channels);

        return CollaborationManager.judge(author, name -> names.contains(name));
    }

    private static VideoGroup createGroup(int type) {
        return VideoGroup.from(new BrowseSection(type, "Section", BrowseSection.TYPE_GRID, 0));
    }

    private static FilterExclusionData getExclusions() {
        return FilterExclusionData.instance(RuntimeEnvironment.getApplication());
    }

    private static Video createVideo(String videoId, String author) {
        Video video = new Video();
        video.videoId = videoId;
        video.title = "Video " + videoId;
        video.author = author;
        video.secondTitle = author + " • 5K views • 3 weeks ago";
        return video;
    }
}
