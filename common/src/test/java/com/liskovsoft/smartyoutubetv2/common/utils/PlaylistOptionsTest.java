package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.PlaylistInfo;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The playlists of Add/Remove from playlist in the order picked in Sort options
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class PlaylistOptionsTest {
    private static final String SEPARATOR = "";
    // As YouTube gives them: Watch later, then the latest edited first
    private final List<PlaylistInfo> mPlaylists = Arrays.asList(playlist("WL", "Watch later"), playlist("PLmusic", "Music"),
            playlist("PLzoo", "zoo"), playlist("PLarc", "Arc"));

    @Before
    public void setUp() {
        GlobalPreferences.instance(getContext());
        // Not from the defaults: another test may have picked another (see MainUIDataTest for them)
        getData().setAddToPlaylistSorting(MainUIData.ADD_TO_PLAYLIST_SORTING_NAME);
    }

    @After
    public void tearDown() {
        getData().setAddToPlaylistSorting(MainUIData.ADD_TO_PLAYLIST_SORTING_NAME);
        Utils.sHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void byTitle() {
        assertEquals(Arrays.asList("Watch later", SEPARATOR, "Arc", "Music", "zoo"), titles(createOptions()));
    }

    @Test
    public void lastEditedKeepsYouTubesOrder() {
        getData().setAddToPlaylistSorting(MainUIData.ADD_TO_PLAYLIST_SORTING_LAST_EDITED);

        assertEquals(Arrays.asList("Watch later", SEPARATOR, "Music", "zoo", "Arc"), titles(createOptions()));
    }

    /**
     * The player keeps the list for the next time: sorted by title, it's still in YouTube's order
     */
    @Test
    public void byTitleLeavesTheListGivenAsItIs() {
        List<PlaylistInfo> given = new ArrayList<>(mPlaylists);

        AppDialogUtil.createPlaylistOptions(getContext(), video(), null, given);

        assertEquals(mPlaylists, given);
    }

    private List<OptionItem> createOptions() {
        return AppDialogUtil.createPlaylistOptions(getContext(), video(), null, mPlaylists);
    }

    private static List<String> titles(List<OptionItem> options) {
        List<String> result = new ArrayList<>();

        for (OptionItem option : options) {
            result.add(option.getTitle().toString());
        }

        return result;
    }

    private static Video video() {
        Video video = new Video();
        video.videoId = "video1";
        return video;
    }

    private static PlaylistInfo playlist(String id, String title) {
        return new PlaylistInfo() {
            @Override
            public String getTitle() {
                return title;
            }

            @Override
            public String getPlaylistId() {
                return id;
            }

            @Override
            public boolean isSelected() {
                return false;
            }

            @Override
            public int getSize() {
                return 0;
            }

            @Override
            public int getCurrentIndex() {
                return -1;
            }

            @Override
            public String toString() {
                return title;
            }
        };
    }

    private static MainUIData getData() {
        return MainUIData.instance(getContext());
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
