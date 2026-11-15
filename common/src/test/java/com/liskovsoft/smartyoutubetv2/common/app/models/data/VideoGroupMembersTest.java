package com.liskovsoft.smartyoutubetv2.common.app.models.data;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.misc.MembersVideos;
import com.liskovsoft.smartyoutubetv2.common.prefs.MembersData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Member videos setting in the groups (see MembersData): hidden by default, also where no section is (the player's suggestions)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoGroupMembersTest {
    private Context mContext;
    private MembersData mData;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        GlobalPreferences.instance(mContext);
        mData = MembersData.instance(mContext);
        mData.setMode(MembersData.MODE_HIDE);
        mData.setSectionEnabled(MediaGroup.TYPE_HOME, true);
        mData.setSectionEnabled(MediaGroup.TYPE_GAMING, true);
        MembersVideos.resetForTesting();
    }

    @After
    public void tearDown() {
        // The defaults
        mData.setMode(MembersData.MODE_HIDE);
        mData.setSectionEnabled(MediaGroup.TYPE_GAMING, true);
        MembersVideos.resetForTesting();
        Utils.sHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void hiddenByDefault() {
        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createMembersVideo("members1", "Members only"));
        home.add(createVideo("video1"));

        assertEquals(1, home.getSize());
        assertEquals("video1", home.get(0).videoId);
    }

    /**
     * Not only in the sections: a hidden one is never played next, so the player's suggestions leave it out too
     */
    @Test
    public void hiddenFromThePlayersSuggestions() {
        VideoGroup suggestions = VideoGroup.from(new ArrayList<>());
        suggestions.setType(MediaGroup.TYPE_SUGGESTIONS);
        suggestions.add(createMembersVideo("members1", "Members first"));
        suggestions.add(createVideo("video1"));

        assertEquals(1, suggestions.getSize());
        assertEquals("video1", suggestions.get(0).videoId);
    }

    @Test
    public void markedWithTheirLabelInMarkMode() {
        mData.setMode(MembersData.MODE_MARK);

        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createMembersVideo("members1", "Members first"));
        home.add(createVideo("video1"));
        VideoGroup suggestions = VideoGroup.from(new ArrayList<>());
        suggestions.setType(MediaGroup.TYPE_SUGGESTIONS);
        suggestions.add(createMembersVideo("members2", "Members only"));

        assertEquals(2, home.getSize());
        assertTrue(home.get(0).isMembersMarked);
        assertEquals("Members first", home.get(0).membersLabel);
        assertFalse(home.get(1).isMembersMarked);
        assertTrue(suggestions.get(0).isMembersMarked);
    }

    @Test
    public void showModeLeavesThemAlone() {
        mData.setMode(MembersData.MODE_SHOW);

        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createMembersVideo("members1", "Members only"));

        assertEquals(1, home.getSize());
        assertFalse(home.get(0).isMembersMarked);
    }

    /**
     * A section left out of the setting shows them as they are
     */
    @Test
    public void sectionLeftOutShowsThem() {
        mData.setSectionEnabled(MediaGroup.TYPE_GAMING, false);

        VideoGroup gaming = createGroup(MediaGroup.TYPE_GAMING);
        gaming.add(createMembersVideo("members1", "Members only"));

        assertEquals(1, gaming.getSize());
        assertFalse(gaming.get(0).isMembersMarked);
    }

    /**
     * Hidden or not, each one is remembered: YouTube's next video of autoplay is only an id (see SuggestionsController.getNext)
     */
    @Test
    public void eachOneIsRemembered() {
        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createMembersVideo("members1", "Members only"));
        home.add(createVideo("video1"));

        assertTrue(MembersVideos.contains("members1"));
        assertFalse(MembersVideos.contains("video1"));
        assertTrue(MembersVideos.isHidden(mContext, Video.from("members1")));
        assertFalse(MembersVideos.isHidden(mContext, Video.from("video1")));

        mData.setMode(MembersData.MODE_MARK);

        assertFalse(MembersVideos.isHidden(mContext, Video.from("members1")));
    }

    @Test
    public void markIsKeptByTheCopy() {
        mData.setMode(MembersData.MODE_MARK);

        VideoGroup home = createGroup(MediaGroup.TYPE_HOME);
        home.add(createMembersVideo("members1", "Members only"));

        Video copy = Video.from(home.get(0));

        assertEquals("Members only", copy.membersLabel);
        assertTrue(copy.isMembersMarked);
    }

    private static VideoGroup createGroup(int type) {
        return VideoGroup.from(new BrowseSection(type, "Section", BrowseSection.TYPE_GRID, 0));
    }

    private static Video createMembersVideo(String videoId, String label) {
        Video video = createVideo(videoId);
        video.membersLabel = label;
        return video;
    }

    private static Video createVideo(String videoId) {
        Video video = new Video();
        video.videoId = videoId;
        video.title = "Video " + videoId;
        video.author = "Channel";
        video.secondTitle = "Channel • 5K views • 3 weeks ago";
        return video;
    }
}
