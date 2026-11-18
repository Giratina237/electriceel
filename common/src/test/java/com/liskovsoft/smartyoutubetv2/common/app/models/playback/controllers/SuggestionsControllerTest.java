package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.mediaserviceinterfaces.ContentService;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Playlist;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SimpleMediaItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.misc.BrowseProcessorManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.WatchLaterData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import io.reactivex.Observable;
import io.reactivex.subjects.PublishSubject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SuggestionsControllerTest {
    private boolean mIsShortsWatchLater;

    @Before
    public void setUp() {
        Playlist.instance().clear();
        GlobalPreferences.instance(RuntimeEnvironment.getApplication()); // the context of VideoGroup's filters
        ShadowToast.reset();
        // On by default: a page of the Shorts section would wait for the account's Watch later, loaded for real (see HiddenVideoResolver)
        WatchLaterData watchLater = WatchLaterData.instance(RuntimeEnvironment.getApplication());
        mIsShortsWatchLater = watchLater.isSectionEnabled(MediaGroup.TYPE_SHORTS);
        watchLater.setSectionEnabled(MediaGroup.TYPE_SHORTS, false);
    }

    @After
    public void tearDown() {
        Playlist.instance().clear();
        WatchLaterData.instance(RuntimeEnvironment.getApplication()).setSectionEnabled(MediaGroup.TYPE_SHORTS, mIsShortsWatchLater);
    }

    @Test
    public void mixAutoplayPrefersInMixItemOverSection() throws Exception {
        Video current = video("current", "RDxxxx");
        Video mixNext = video("mixNext", "RDxxxx");
        current.nextMediaItem = SimpleMediaItem.from(mixNext);

        TestController controller = new TestController(current);
        controller.onNewVideo(current);

        Field field = SuggestionsController.class.getDeclaredField("mNextSectionVideo");
        field.setAccessible(true);
        field.set(controller, video("section", null));

        assertEquals("mixNext", controller.getNext().videoId);
    }

    /**
     * A Short of the Shorts section or of the Shorts row of Home goes on with its group: not with YouTube's next video, a regular one
     * (SmartTube #6490)
     */
    @Test
    public void aShortOfAShortsGroupGoesOnWithItsGroupNotYouTubesNext() {
        for (int type : new int[]{MediaGroup.TYPE_SHORTS, MediaGroup.TYPE_HOME}) {
            VideoGroup group = shortsGroup(type, "page2", "s1", "s2", "s3");
            Video current = group.get(0);
            current.nextMediaItem = SimpleMediaItem.from(video("regular", null));
            ShortsController controller = new ShortsController(current);

            assertTrue("type " + type, controller.isShortsGroupPlaying());
            assertEquals("type " + type, "s2", controller.getNext().videoId);
        }
    }

    /**
     * The last of its group's cards: none till the group's next page has it, not YouTube's next video
     */
    @Test
    public void theLastShortOfItsGroupHasNoNextTillTheNextPage() {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SHORTS, "page2", "s1", "s2");
        Video current = group.get(1);
        current.nextMediaItem = SimpleMediaItem.from(video("regular", null));
        ShortsController controller = new ShortsController(current);

        assertTrue(controller.isShortsGroupPlaying());
        assertNull(controller.getNext());
    }

    /**
     * A Short of another section, as Subscriptions, goes on as a video does: with YouTube's next one
     */
    @Test
    public void aShortOfAnotherSectionGoesOnWithYouTubesNext() {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SUBSCRIPTIONS, "page2", "s1", "s2");
        Video current = group.get(1);
        current.nextMediaItem = SimpleMediaItem.from(video("regular", null));
        ShortsController controller = new ShortsController(current);

        assertFalse(controller.isShortsGroupPlaying());
        assertEquals("regular", controller.getNext().videoId);
    }

    /**
     * Down on the last card: the group's next page is loaded, the Short says it waits, and the next Short opens once the page has it
     */
    @Test
    public void theNextShortOpensOnceTheGroupsNextPageHasIt() {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SHORTS, "page2", "s1", "s2");
        ShortsController controller = new ShortsController(group.get(1));
        PublishSubject<MediaGroup> page = PublishSubject.create();
        controller.mPage = page;
        int[] runs = {0};

        controller.loadNextShort(() -> runs[0]++);

        assertEquals(1, controller.mLoads);
        assertEquals("page2", controller.mLoaded.getNextPageKey());
        assertEquals("Please wait while data is loading…", ShadowToast.getTextOfLatestToast());
        assertEquals(0, runs[0]);

        page.onNext(page("page3", "s3", "s4"));
        page.onComplete();

        assertEquals(1, runs[0]);
        assertEquals(4, group.getSize());
        assertEquals("s3", controller.getNext().videoId);
        assertEquals(1, ShadowToast.shownToastCount());
    }

    /**
     * The page didn't load: the Short stays, and says so. Asked again, the page is loaded again.
     */
    @Test
    public void aFailedLoadKeepsTheShortAndSaysSo() {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SHORTS, "page2", "s1", "s2");
        ShortsController controller = new ShortsController(group.get(1));
        controller.mPage = Observable.error(new IllegalStateException("offline"));
        int[] runs = {0};

        controller.loadNextShort(() -> runs[0]++);

        assertEquals("Couldn't load more Shorts", ShadowToast.getTextOfLatestToast());

        controller.loadNextShort(() -> runs[0]++);

        assertEquals(2, controller.mLoads);
        assertEquals(0, runs[0]);
        assertEquals(2, group.getSize());
        assertEquals("Couldn't load more Shorts", ShadowToast.getTextOfLatestToast());
    }

    /**
     * A page without a Short to play: the Short stays, and says the page had none to load
     */
    @Test
    public void aPageWithoutANextShortKeepsTheShort() {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SHORTS, "page2", "s1", "s2");
        ShortsController controller = new ShortsController(group.get(1));
        controller.mPage = Observable.just(page("page3"));
        int[] runs = {0};

        controller.loadNextShort(() -> runs[0]++);

        assertEquals(0, runs[0]);
        assertEquals("Couldn't load more Shorts", ShadowToast.getTextOfLatestToast());
    }

    /**
     * The end of the feed (no next page): the Short stays, and says there are no more. The service has none to give (see
     * YouTubeContentService.continueGroup: null, RxHelper's error).
     */
    @Test
    public void theEndOfTheFeedSaysThereAreNoMoreShorts() {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SHORTS, null, "s1", "s2");
        ShortsController controller = new ShortsController(group.get(1));
        controller.mPage = Observable.error(new NoSuchElementException("fromNullable result is null"));
        int[] runs = {0};

        controller.loadNextShort(() -> runs[0]++);

        assertEquals(0, runs[0]);
        assertEquals("No more Shorts", ShadowToast.getTextOfLatestToast());
    }

    /**
     * Another video opened meanwhile: the page is added to the group, the callback doesn't run
     */
    @Test
    public void thePageOfAShortThatNoLongerPlaysDoesntOpenItsNext() {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SHORTS, "page2", "s1", "s2");
        ShortsController controller = new ShortsController(group.get(1));
        PublishSubject<MediaGroup> page = PublishSubject.create();
        controller.mPage = page;
        int[] runs = {0};

        controller.loadNextShort(() -> runs[0]++);
        controller.mVideo = video("another", null);
        page.onNext(page("page3", "s3"));
        page.onComplete();

        assertEquals(0, runs[0]);
        assertEquals(3, group.getSize());
    }

    /**
     * Asked again while the page loads: one load, and the last callback runs only
     */
    @Test
    public void askedAgainWhileThePageLoadsTheLastCallbackRuns() {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SHORTS, "page2", "s1", "s2");
        ShortsController controller = new ShortsController(group.get(1));
        PublishSubject<MediaGroup> page = PublishSubject.create();
        controller.mPage = page;
        List<String> runs = new ArrayList<>();

        controller.loadNextShort(() -> runs.add("first"));
        controller.loadNextShort(() -> runs.add("second"));
        page.onNext(page("page3", "s3"));
        page.onComplete();

        assertEquals(1, controller.mLoads);
        assertEquals(1, runs.size());
        assertEquals("second", runs.get(0));
    }

    /**
     * The group's next page is loaded ahead, from the Short 5 cards before the end of the group, once
     */
    @Test
    public void theNextPageIsLoadedAheadFiveCardsBeforeTheEnd() throws Exception {
        VideoGroup group = shortsGroup(MediaGroup.TYPE_SHORTS, "page2", "s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8");
        ShortsController controller = new ShortsController(group.get(1));
        controller.mPage = Observable.never();

        continueShortsGroupIfNeeded(controller, group.get(1)); // 6 cards after it
        assertEquals(0, controller.mLoads);

        continueShortsGroupIfNeeded(controller, group.get(2)); // 5
        assertEquals(1, controller.mLoads);

        continueShortsGroupIfNeeded(controller, group.get(3)); // the load runs
        assertEquals(1, controller.mLoads);
    }

    /**
     * No next page (the end of the feed), or not a Shorts group: nothing to load ahead
     */
    @Test
    public void nothingIsLoadedAheadWithoutANextPageOrOutsideAShortsGroup() throws Exception {
        VideoGroup end = shortsGroup(MediaGroup.TYPE_SHORTS, null, "s1", "s2");
        VideoGroup subscriptions = shortsGroup(MediaGroup.TYPE_SUBSCRIPTIONS, "page2", "s3", "s4");
        ShortsController controller = new ShortsController(end.get(0));
        controller.mPage = Observable.never();

        continueShortsGroupIfNeeded(controller, end.get(0));
        continueShortsGroupIfNeeded(controller, subscriptions.get(0));

        assertEquals(0, controller.mLoads);
    }

    private static void continueShortsGroupIfNeeded(SuggestionsController controller, Video video) throws Exception {
        Method method = SuggestionsController.class.getDeclaredMethod("continueShortsGroupIfNeeded", Video.class);
        method.setAccessible(true);
        method.invoke(controller, video);
    }

    private static Video video(String videoId, String playlistId) {
        Video video = new Video();
        video.videoId = videoId;
        video.playlistId = playlistId;
        return video;
    }

    /**
     * A group of Shorts of a section (see MediaGroup.TYPE_SHORTS), as the browse fragment has it: its cards and its next page
     */
    private static VideoGroup shortsGroup(int type, String nextPageKey, String... videoIds) {
        MediaGroup mediaGroup = mediaGroup(type, nextPageKey, videoIds);
        return VideoGroup.from(mediaGroup, new BrowseSection(type, "Shorts", BrowseSection.TYPE_GRID, 0));
    }

    /**
     * The group's next page, of the Shorts section
     */
    private static MediaGroup page(String nextPageKey, String... videoIds) {
        return mediaGroup(MediaGroup.TYPE_SHORTS, nextPageKey, videoIds);
    }

    private static MediaGroup mediaGroup(int type, String nextPageKey, String... videoIds) {
        List<MediaItem> items = new ArrayList<>();

        for (String videoId : videoIds) {
            Map<String, Object> values = new HashMap<>();
            values.put("getVideoId", videoId);
            values.put("getTitle", "Short " + videoId);
            values.put("getType", MediaItem.TYPE_VIDEO);
            values.put("isShorts", true);
            items.add(stub(MediaItem.class, values));
        }

        Map<String, Object> values = new HashMap<>();
        values.put("getType", type);
        values.put("getTitle", "Shorts");
        values.put("getMediaItems", items);
        values.put("getNextPageKey", nextPageKey);

        return stub(MediaGroup.class, values);
    }

    /**
     * By method name; the defaults (false, 0, null) for the others, and an object's own identity
     */
    private static <T> T stub(Class<T> type, Map<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return type.getSimpleName() + values;
            }

            if (values.containsKey(method.getName())) {
                return values.get(method.getName());
            }

            Class<?> returned = method.getReturnType();

            if (returned == boolean.class) {
                return false;
            } else if (returned == int.class) {
                return 0;
            } else if (returned == long.class) {
                return 0L;
            } else if (returned == float.class) {
                return 0f;
            }

            return null;
        }));
    }

    private static class TestController extends SuggestionsController {
        private final PlaybackView mPlayer;
        private final Video mVideo;

        private TestController(Video video) {
            mPlayer = (PlaybackView) Proxy.newProxyInstance(
                    PlaybackView.class.getClassLoader(),
                    new Class<?>[]{PlaybackView.class},
                    (proxy, method, args) -> null);
            mVideo = video;
        }

        @Override
        public PlaybackView getPlayer() {
            return mPlayer;
        }

        @Override
        public Video getVideo() {
            return mVideo;
        }
    }

    /**
     * Plays a Short, with the app's context (see MessageHelpers): its group's next pages are the one given, and counted
     */
    private static class ShortsController extends SuggestionsController {
        private final PlaybackView mPlayer = stub(PlaybackView.class, new HashMap<>());
        private Video mVideo;
        private Observable<MediaGroup> mPage;
        private MediaGroup mLoaded;
        private int mLoads;

        private ShortsController(Video video) {
            mVideo = video;
            setAltContext(RuntimeEnvironment.getApplication());
            ContentService defaults = stub(ContentService.class, new HashMap<>());
            ContentService service = (ContentService) Proxy.newProxyInstance(ContentService.class.getClassLoader(),
                    new Class<?>[]{ContentService.class}, (proxy, method, args) -> {
                        if ("continueGroupObserve".equals(method.getName())) {
                            mLoads++;
                            mLoaded = (MediaGroup) args[0];
                            return mPage;
                        }

                        return method.invoke(defaults, args);
                    });

            try {
                setField("mContentService", service);
                setField("mBrowseProcessor", new BrowseProcessorManager(RuntimeEnvironment.getApplication(), item -> {}));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void setField(String name, Object value) throws Exception {
            Field field = SuggestionsController.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(this, value);
        }

        @Override
        public PlaybackView getPlayer() {
            return mPlayer;
        }

        @Override
        public Video getVideo() {
            return mVideo;
        }
    }
}
