package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.util.Pair;

import com.liskovsoft.sharedutils.helpers.Helpers;
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
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A channel is matched by its channel id, by its name only when the card or the entry has none (yuliskov/SmartTube#6311)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BlockedChannelDataTest {
    private static final String NEWS_ID = "UCaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER_NEWS_ID = "UCbbbbbbbbbbbbbbbbbbbbbb";
    private static final String NEWS_PLAYLIST_ID = "VLPLaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Before
    public void setUp() {
        getPrefs().onAccountChanged(null); // the anonymous account
        clearSaved();
    }

    @After
    public void tearDown() {
        Utils.sHandler.removeCallbacksAndMessages(null);
        getPrefs().onAccountChanged(null);
        clearSaved();
    }

    @Test
    public void otherChannelOfTheSameNameIsNotBlocked() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");

        assertTrue(data.containsChannel(NEWS_ID, "News"));
        assertFalse(data.containsChannel(OTHER_NEWS_ID, "News"));
        assertFalse(data.containsChannel(OTHER_NEWS_ID, "news"));
    }

    @Test
    public void renamedChannelIsStillBlocked() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");

        assertTrue(data.containsChannel(NEWS_ID, "News Daily"));
    }

    /**
     * Most cards of Home have no id
     */
    @Test
    public void cardWithoutIdIsMatchedByName() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");

        assertTrue(data.containsChannel(null, "News"));
        assertTrue(data.containsChannel(null, "NEWS"));
        assertFalse(data.containsChannel(null, "News Daily"));
    }

    /**
     * Blocked from a card without the id: the name is all there is to match
     */
    @Test
    public void channelBlockedWithoutIdIsMatchedByName() {
        BlockedChannelData data = getData();

        data.addChannel(null, "News");

        assertTrue(data.containsChannel(NEWS_ID, "News"));
        assertTrue(data.containsChannel(null, "news"));
        assertFalse(data.containsChannel(NEWS_ID, "News Daily"));
    }

    /**
     * A card of a playlist has its page's id ("VL..."), not its channel's: the name tells
     */
    @Test
    public void playlistOfBlockedChannelIsBlocked() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");

        assertTrue(data.containsChannel(NEWS_PLAYLIST_ID, "News"));
        assertFalse(data.containsChannel(NEWS_PLAYLIST_ID, "News Daily"));
    }

    @Test
    public void channelBlockedFromPlaylistIsMatchedByName() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_PLAYLIST_ID, "News");

        assertTrue(data.containsChannel(NEWS_ID, "News"));
        assertFalse(data.containsChannel(NEWS_ID, "News Daily"));
    }

    @Test
    public void twoChannelsOfTheSameNameAreBothBlocked() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");
        data.addChannel(OTHER_NEWS_ID, "News");

        assertEquals(2, data.getChannelCount());
        assertTrue(data.containsChannel(NEWS_ID, "News"));
        assertTrue(data.containsChannel(OTHER_NEWS_ID, "News"));
    }

    @Test
    public void unblockingOneOfTwoChannelsOfTheSameNameKeepsTheOther() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");
        data.addChannel(OTHER_NEWS_ID, "News");
        data.removeChannel(OTHER_NEWS_ID, "News");

        assertTrue(data.containsChannel(NEWS_ID, "News"));
        assertFalse(data.containsChannel(OTHER_NEWS_ID, "News"));
    }

    @Test
    public void blockingARenamedChannelAgainReplacesItsEntry() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");
        data.addChannel(NEWS_ID, "News Daily");

        assertEquals(Collections.singletonList(new Pair<>(NEWS_ID, "News Daily")), data.getChannelIdsWithNames());
    }

    /**
     * Unblock of a video of the channel after it's renamed
     */
    @Test
    public void renamedChannelIsUnblocked() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");
        data.removeChannel(NEWS_ID, "News Daily");

        assertTrue(data.isEmpty());
    }

    @Test
    public void channelsAreSortedByName() {
        BlockedChannelData data = getData();

        data.addChannel("UCbeta", "beta");
        data.addChannel(OTHER_NEWS_ID, "News");
        data.addChannel("UCalpha", "Alpha");
        data.addChannel(NEWS_ID, "News");

        assertEquals(Arrays.asList("Alpha", "beta", "News", "News"), names(data));
        assertEquals(NEWS_ID, data.getChannelIdsWithNames().get(2).first);
    }

    @Test
    public void channelsSurviveRestart() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");
        data.addChannel(OTHER_NEWS_ID, "News");
        data.addChannel(null, "Gaming");
        data.persistPendingData();
        BlockedChannelData.resetInstanceForTesting();

        BlockedChannelData restored = getData();

        assertEquals(3, restored.getChannelCount());
        assertTrue(restored.containsChannel(OTHER_NEWS_ID, "News"));
        assertFalse(restored.containsChannel("UCcccccccccccccccccccccc", "News"));
        assertTrue(restored.containsChannel("UCcccccccccccccccccccccc", "Gaming"));
    }

    /**
     * The list of the very old versions (id -> name) adds only the channels that aren't blocked already
     */
    @Test
    public void oldListAddsOnlyTheChannelsNotBlocked() {
        List<String> channels = Collections.singletonList(Helpers.mergeObj(NEWS_ID, "News Daily"));
        getPrefs().setProfileData(BlockedChannelData.BLOCKED_CHANNEL_DATA,
                Helpers.mergeData(channels, Collections.singletonMap(NEWS_ID, "News")));
        BlockedChannelData.resetInstanceForTesting();

        assertEquals(Collections.singletonList(new Pair<>(NEWS_ID, "News Daily")), getData().getChannelIdsWithNames());

        getPrefs().setProfileData(BlockedChannelData.BLOCKED_CHANNEL_DATA,
                Helpers.mergeData(channels, Collections.singletonMap(OTHER_NEWS_ID, "News")));
        BlockedChannelData.resetInstanceForTesting();

        assertEquals(2, getData().getChannelCount());
    }

    /**
     * Blocked from a card of Home, then its video's channel is found
     */
    @Test
    public void channelBlockedByNameGetsItsId() {
        BlockedChannelData data = getData();

        data.addChannel(null, "News");
        data.setChannelId("News", NEWS_ID);

        assertEquals(Collections.singletonList(new Pair<>(NEWS_ID, "News")), data.getChannelIdsWithNames());
        assertFalse(data.containsChannel(OTHER_NEWS_ID, "News"));
    }

    @Test
    public void unblockedChannelDoesNotGetItsId() {
        BlockedChannelData data = getData();

        data.addChannel(null, "News");
        data.removeChannel(null, "News");
        data.setChannelId("News", NEWS_ID);

        assertTrue(data.isEmpty());
    }

    @Test
    public void channelBlockedWithIdKeepsIt() {
        BlockedChannelData data = getData();

        data.addChannel(OTHER_NEWS_ID, "News");
        data.setChannelId("News", NEWS_ID);

        assertEquals(Collections.singletonList(new Pair<>(OTHER_NEWS_ID, "News")), data.getChannelIdsWithNames());
    }

    /**
     * Only a channel blocked with its id might be another channel than the card's of the same name
     */
    @Test
    public void cardMightBeOfChannelBlockedWithId() {
        BlockedChannelData data = getData();

        data.addChannel(NEWS_ID, "News");
        data.addChannel(null, "Gaming");
        data.addChannel(NEWS_PLAYLIST_ID, "Music");

        assertTrue(data.mayContainChannel(null, "news"));
        assertTrue(data.mayContainChannel(NEWS_ID, "News Daily"));
        assertFalse(data.mayContainChannel(OTHER_NEWS_ID, "News Daily"));
        assertFalse(data.mayContainChannel(null, "Gaming"));
        assertFalse(data.mayContainChannel(null, "Music"));
    }

    @Test
    public void channelIdsAreTheChannelsNotThePlaylists() {
        BlockedChannelData data = getData();

        data.addChannel(null, "Gaming");
        data.addChannel(NEWS_PLAYLIST_ID, "Music");

        assertFalse(data.hasChannelIds());

        data.addChannel(NEWS_ID, "News");

        assertTrue(data.hasChannelIds());
    }

    private static List<String> names(BlockedChannelData data) {
        List<String> result = new ArrayList<>();

        for (Pair<String, String> channel : data.getChannelIdsWithNames()) {
            result.add(channel.second);
        }

        return result;
    }

    private static void clearSaved() {
        TestAccounts.clearSaved(getPrefs(), BlockedChannelData.BLOCKED_CHANNEL_DATA);
        BlockedChannelData.resetInstanceForTesting();
    }

    private static AppPrefs getPrefs() {
        return AppPrefs.instance(RuntimeEnvironment.getApplication());
    }

    private static BlockedChannelData getData() {
        return BlockedChannelData.instance(RuntimeEnvironment.getApplication());
    }
}
