package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.util.Pair;

import com.liskovsoft.mediaserviceinterfaces.data.MediaItem;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.BlockedChannelData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.reactivex.Observable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The cards of Home have the channel name alone: the channel is found by the name, then by the video (see BlockedChannelManager)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BlockedChannelManagerTest {
    private static final String NEWS_ID = "UCaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FAKE_NEWS_ID = "UCbbbbbbbbbbbbbbbbbbbbbb"; // another channel named "News"
    private static final Map<String, Map<String, String>> HANDLES = new HashMap<>(); // video id -> its owners' handles -> their channel ids

    static {
        HANDLES.put("news", Collections.singletonMap("@News", NEWS_ID));
        HANDLES.put("renamed", Collections.singletonMap("@News", NEWS_ID)); // the channel is now "News Daily"
        HANDLES.put("fake", Collections.singletonMap("@FakeNews", FAKE_NEWS_ID));
    }

    private final Context mContext = RuntimeEnvironment.getApplication();

    @Before
    public void setUp() {
        GlobalPreferences.instance(mContext);
        getData().clear();
        AiSListManager aiSListManager = AiSListManager.instance(mContext);
        aiSListManager.setListsForTesting(Collections.emptySet(), Collections.emptySet());
        aiSListManager.setHandleLookupForTesting(this::lookUp);
        BlockedChannelManager.instance(mContext).resetForTesting(this::lookUp);
    }

    @After
    public void tearDown() {
        getData().clear();
        Utils.sHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void homeCardIsBlockedWithItsChannelId() {
        getManager().block(createVideo("news", "News"));

        assertEquals(Collections.singletonList(new Pair<>(NEWS_ID, "News")), getData().getChannelIdsWithNames());
        assertFalse(getManager().isBlocked(createVideo("fake", "News", FAKE_NEWS_ID)));
    }

    @Test
    public void otherChannelOfTheSameNameIsNotBlocked() {
        getData().addChannel(NEWS_ID, "News");
        Video fake = createVideo("fake", "News");
        Video news = createVideo("news", "News");

        resolve(fake, news);

        assertFalse(getManager().isBlocked(fake));
        assertTrue(getManager().isBlocked(news));
    }

    @Test
    public void otherChannelOfTheSameNameCanBeBlockedToo() {
        getData().addChannel(NEWS_ID, "News");
        Video fake = createVideo("fake", "News");

        resolve(fake);
        getManager().block(fake);

        assertEquals(2, getData().getChannelCount());
        assertTrue(getData().containsChannel(NEWS_ID, "News"));
        assertTrue(getData().containsChannel(FAKE_NEWS_ID, "News"));
    }

    @Test
    public void renamedChannelIsBlocked() {
        getData().addChannel(NEWS_ID, "News");
        Video renamed = createVideo("renamed", "News Daily");

        resolve(renamed);

        assertTrue(getManager().isBlocked(renamed));
    }

    /**
     * The id its name was found to have tells
     */
    @Test
    public void renamedChannelIsBlockedWhenItsVideoIsNotFound() {
        getData().addChannel(NEWS_ID, "News");
        AiSListManager.instance(mContext).resolveHandles(Collections.singletonMap("News Daily", "renamed")).subscribe();
        Video unknown = createVideo("unknown", "News Daily");

        getManager().resolve(Collections.singletonList(unknown)).subscribe();

        assertTrue(getManager().isBlocked(unknown));
    }

    /**
     * As before the ids
     */
    @Test
    public void channelIsBlockedByNameWhenItsVideoIsNotFound() {
        getData().addChannel(NEWS_ID, "News");
        Video unknown = createVideo("unknown", "News");

        resolve(unknown);

        assertTrue(getManager().isBlocked(unknown));
    }

    @Test
    public void renamedChannelIsUnblockedFromItsCard() {
        getData().addChannel(NEWS_ID, "News");
        Video renamed = createVideo("renamed", "News Daily");

        resolve(renamed);
        getManager().unblock(renamed);

        assertTrue(getData().isEmpty());
    }

    /**
     * A playlist's video id is its first video's, maybe of another channel
     */
    @Test
    public void playlistIsMatchedByName() {
        getData().addChannel(NEWS_ID, "News");
        Video playlist = createVideo("fake", "News");
        playlist.itemType = MediaItem.TYPE_PLAYLIST;

        resolve(playlist);

        assertTrue(getManager().isBlocked(playlist));
    }

    /**
     * Each name once, then the cards that might be the blocked channel's by their video, as HiddenVideoResolver does
     */
    private void resolve(Video... videos) {
        Map<String, String> videoIdByKey = new HashMap<>();

        for (Video video : videos) {
            videoIdByKey.putIfAbsent(AiSListManager.getLookupKey(video), video.videoId);
        }

        AiSListManager.instance(mContext).resolveHandles(videoIdByKey).subscribe();
        getManager().resolve(Arrays.asList(videos)).subscribe();
    }

    private Observable<Map<String, String>> lookUp(String videoId) {
        Map<String, String> handles = HANDLES.get(videoId);

        return handles != null ? Observable.just(handles) : Observable.error(new IllegalStateException("No handle"));
    }

    private BlockedChannelManager getManager() {
        return BlockedChannelManager.instance(mContext);
    }

    private BlockedChannelData getData() {
        return BlockedChannelData.instance(mContext);
    }

    /**
     * A card of Home: the channel name alone
     */
    private static Video createVideo(String videoId, String author) {
        return createVideo(videoId, author, null);
    }

    private static Video createVideo(String videoId, String author, String channelId) {
        Video video = new Video();
        video.videoId = videoId;
        video.title = "Video " + videoId;
        video.author = author;
        video.channelId = channelId;
        video.secondTitle = author + " • 5K views • 3 weeks ago";
        return video;
    }
}
