package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService.State;
import com.liskovsoft.smartyoutubetv2.common.prefs.HomeRowsData.Row;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hide watched from Home or Subscriptions (see MediaServiceData.CONTENT_WATCHED_*) also hides the videos watched in the app.
 * YouTube's progress is filtered in MediaServiceCore (see MediaGroupOptions), but YouTube may still have a video played to the
 * end in the app as barely watched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoGroupHideWatchedTest {
    private static final BrowseSection HOME = new BrowseSection(MediaGroup.TYPE_HOME, "Home", BrowseSection.TYPE_ROW, 0);
    private static final long DURATION_MS = 600_000;
    private MediaServiceData mMediaServiceData;
    private VideoStateService mStateService;
    private final List<String> mPlayedIds = new ArrayList<>();
    private int mVideoCount;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        GlobalPreferences.instance(context);
        mMediaServiceData = MediaServiceData.instance();
        mStateService = VideoStateService.instance(context);
    }

    @After
    public void tearDown() {
        // As in the other tests: MediaServiceData doesn't read its saved values in them
        mMediaServiceData.setHiddenContentInSettings(0);

        for (String videoId : mPlayedIds) {
            mStateService.removeByVideoId(videoId);
        }

        Utils.sHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void videoWatchedInTheAppIsShownByDefault() {
        assertEquals(1, createSection(MediaGroup.TYPE_HOME, createWatchedInApp()).getSize());
        assertEquals(1, createSection(MediaGroup.TYPE_SUBSCRIPTIONS, createWatchedInApp()).getSize());
    }

    /**
     * YouTube's card says barely watched, or nothing (e.g. incognito, or history paused): the app played it to the end
     */
    @Test
    public void videoWatchedInTheAppIsHiddenFromHome() {
        mMediaServiceData.setContentHidden(MediaServiceData.CONTENT_WATCHED_HOME, true);

        Video barelyOnCard = createWatchedInApp();
        barelyOnCard.percentWatched = 5;
        Video started = createVideo(-1);
        savePlayed(started, DURATION_MS / 2);

        VideoGroup home = createSection(MediaGroup.TYPE_HOME, barelyOnCard, createWatchedInApp(), started, createVideo(-1));

        assertEquals(2, home.getSize());
        assertEquals(started.videoId, home.get(0).videoId);
    }

    @Test
    public void eachSectionGoesByItsOwnOption() {
        mMediaServiceData.setContentHidden(MediaServiceData.CONTENT_WATCHED_HOME, true);

        assertEquals(0, createSection(MediaGroup.TYPE_HOME, createWatchedInApp()).getSize());
        assertEquals(1, createSection(MediaGroup.TYPE_SUBSCRIPTIONS, createWatchedInApp()).getSize());

        mMediaServiceData.setHiddenContentInSettings(0);
        mMediaServiceData.setContentHidden(MediaServiceData.CONTENT_WATCHED_SUBSCRIPTIONS, true);

        assertEquals(1, createSection(MediaGroup.TYPE_HOME, createWatchedInApp()).getSize());
        assertEquals(0, createSection(MediaGroup.TYPE_SUBSCRIPTIONS, createWatchedInApp()).getSize());
    }

    /**
     * As MediaServiceCore filters it: by the feed, Subscriptions
     */
    @Test
    public void subscriptionsRowOfHomeGoesBySubscriptions() {
        mMediaServiceData.setContentHidden(MediaServiceData.CONTENT_WATCHED_SUBSCRIPTIONS, true);

        VideoGroup subscriptionsRow = VideoGroup.from(new TestMediaGroup(MediaGroup.TYPE_SUBSCRIPTIONS, createWatchedInApp()), HOME, 0,
                null, Row.subscriptions());

        assertEquals(0, subscriptionsRow.getSize());
    }

    @Test
    public void otherSectionsAreLeftAlone() {
        mMediaServiceData.setContentHidden(MediaServiceData.CONTENT_WATCHED_HOME, true);
        mMediaServiceData.setContentHidden(MediaServiceData.CONTENT_WATCHED_SUBSCRIPTIONS, true);

        int[] sections = {MediaGroup.TYPE_HISTORY, MediaGroup.TYPE_WATCH_LATER, MediaGroup.TYPE_USER_PLAYLISTS, MediaGroup.TYPE_GAMING,
                MediaGroup.TYPE_CHANNEL, MediaGroup.TYPE_SEARCH, MediaGroup.TYPE_SUGGESTIONS};

        for (int sectionId : sections) {
            assertEquals("section " + sectionId, 1, createSection(sectionId, createWatchedInApp()).getSize());
        }
    }

    /**
     * A live stream, a chapter, and a card without a video (e.g. a channel or a playlist) are left alone
     */
    @Test
    public void onlyVideosAreHidden() {
        mMediaServiceData.setContentHidden(MediaServiceData.CONTENT_WATCHED_HOME, true);

        Video live = createWatchedInApp();
        live.isLive = true;
        Video chapter = createWatchedInApp();
        chapter.isChapter = true;
        Video playlist = new Video();
        playlist.itemType = MediaItem.TYPE_PLAYLIST;
        playlist.playlistId = "PLclimbing";
        playlist.title = "Climbing";

        assertEquals(3, createSection(MediaGroup.TYPE_HOME, live, chapter, playlist).getSize());
    }

    /**
     * A row left with only cards without a video isn't shown (see BrowsePresenter)
     */
    @Test
    public void hiddenWatchedVideosAreCounted() {
        mMediaServiceData.setContentHidden(MediaServiceData.CONTENT_WATCHED_HOME, true);

        Video channel = new Video();
        channel.itemType = MediaItem.TYPE_CHANNEL;
        channel.channelId = "UCclimbing";
        channel.title = "Climbing";

        VideoGroup home = createSection(MediaGroup.TYPE_HOME, createWatchedInApp(), channel);

        assertEquals(1, home.getSize());
        assertTrue(home.hasOnlyHiddenVideos());
    }

    private static VideoGroup createSection(int sectionId, Video... videos) {
        VideoGroup group = VideoGroup.from(new BrowseSection(sectionId, "Section", BrowseSection.TYPE_GRID, 0));

        for (Video video : videos) {
            group.add(video);
        }

        return group;
    }

    /**
     * Played to 90% in the app, nothing on YouTube's card
     */
    private Video createWatchedInApp() {
        Video video = createVideo(-1);
        savePlayed(video, DURATION_MS * 9 / 10);
        return video;
    }

    private void savePlayed(Video video, long positionMs) {
        mStateService.save(new State(video, positionMs, DURATION_MS));
        mPlayedIds.add(video.videoId);
    }

    private Video createVideo(float percentWatched) {
        mVideoCount++;
        Video video = new Video();
        video.itemType = MediaItem.TYPE_VIDEO;
        video.videoId = "watchedVideo" + mVideoCount;
        video.title = "Video " + mVideoCount;
        video.percentWatched = percentWatched;
        return video;
    }

    private static final class TestMediaGroup implements MediaGroup {
        private final int mType;
        private final List<MediaItem> mItems = new ArrayList<>();

        TestMediaGroup(int type, Video... videos) {
            mType = type;

            for (Video video : videos) {
                mItems.add(SimpleMediaItem.from(video));
            }
        }

        @Override
        public int getType() {
            return mType;
        }

        @Override
        public List<MediaItem> getMediaItems() {
            return mItems;
        }

        @Override
        public String getTitle() {
            return null;
        }

        @Override
        public String getChannelId() {
            return null;
        }

        @Override
        public String getParams() {
            return null;
        }

        @Override
        public String getReloadPageKey() {
            return null;
        }

        @Override
        public String getNextPageKey() {
            return null;
        }

        @Override
        public String getChannelUrl() {
            return null;
        }

        @Override
        public boolean isEmpty() {
            return mItems.isEmpty();
        }

        @Override
        public int getTopic() {
            return MediaGroup.TOPIC_NONE;
        }

        @Override
        public boolean isChannelRow() {
            return false;
        }

        @Override
        public boolean isSearchTopicRow() {
            return false;
        }

        @Override
        public int getRowKind() {
            return ROW_KIND_NONE;
        }

        @Override
        public int getFilteredVideoCount() {
            return 0;
        }
    }
}
