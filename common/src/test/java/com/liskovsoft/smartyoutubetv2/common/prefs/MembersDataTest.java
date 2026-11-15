package com.liskovsoft.smartyoutubetv2.common.prefs;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shared behavior of SectionFilterData is covered by CollaborationsDataTest
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MembersDataTest {
    /**
     * No section: e.g. the player's suggestions (see VideoGroup.getFilterSectionId)
     */
    private static final int NO_SECTION = -1;

    @Before
    public void setUp() {
        getPrefs().onAccountChanged(null); // the anonymous account
        clearSaved();
    }

    @After
    public void tearDown() {
        Utils.sHandler.removeCallbacksAndMessages(null);
        getPrefs().onAccountChanged(null); // the anonymous account
        clearSaved();
    }

    @Test
    public void hiddenEverywhereByDefaultWithAGreenLabel() {
        MembersData data = getData();

        assertEquals(MembersData.MODE_HIDE, data.getMode());
        assertEquals(AiSListFilterData.MARK_COLOR_GREEN, data.getMarkColor());
        assertTrue(data.isHidingEnabledIn(MediaGroup.TYPE_HOME));
        assertTrue(data.isHidingEnabledIn(MediaGroup.TYPE_SUBSCRIPTIONS));
        assertTrue(data.isHidingEnabledIn(MediaGroup.TYPE_CHANNEL));
        assertTrue(data.isHidingEnabledIn(NO_SECTION));
        assertTrue(data.isHidingEverywhere());
    }

    /**
     * History shows what was watched: marked there, as with the other filters
     */
    @Test
    public void markedInHistoryWhenHidden() {
        MembersData data = getData();

        assertFalse(data.isHidingEnabledIn(MediaGroup.TYPE_HISTORY));
        assertTrue(data.isMarkingEnabledIn(MediaGroup.TYPE_HISTORY));
    }

    @Test
    public void markedEverywhereWhenSetToMark() {
        MembersData data = getData();
        data.setMode(MembersData.MODE_MARK);

        assertFalse(data.isHidingEnabledIn(MediaGroup.TYPE_HOME));
        assertFalse(data.isHidingEnabledIn(NO_SECTION));
        assertFalse(data.isHidingEverywhere());
        assertTrue(data.isMarkingEnabledIn(MediaGroup.TYPE_HOME));
        assertTrue(data.isMarkingEnabledIn(NO_SECTION));
    }

    @Test
    public void neitherWhenSetToShow() {
        MembersData data = getData();
        data.setMode(MembersData.MODE_SHOW);

        assertFalse(data.isHidingEnabledIn(NO_SECTION));
        assertFalse(data.isMarkingEnabledIn(NO_SECTION));
        assertFalse(data.isMarkingEnabledIn(MediaGroup.TYPE_HOME));
    }

    /**
     * A section left out of the setting shows them as they are; no section still hides them, so none is played next
     */
    @Test
    public void sectionLeftOutIsLeftAlone() {
        MembersData data = getData();
        data.setSectionEnabled(MediaGroup.TYPE_HOME, false);

        assertFalse(data.isHidingEnabledIn(MediaGroup.TYPE_HOME));
        assertFalse(data.isMarkingEnabledIn(MediaGroup.TYPE_HOME));
        assertTrue(data.isHidingEnabledIn(NO_SECTION));
    }

    /**
     * The quick toggle turned off marks them instead, everywhere
     */
    @Test
    public void quickToggleOffMarksInstead() {
        MembersData data = getData();
        data.setQuickToggleEnabled(true);
        data.setQuickToggleHiding(false);

        assertFalse(data.isHidingEnabledIn(MediaGroup.TYPE_HOME));
        assertFalse(data.isHidingEnabledIn(NO_SECTION));
        assertTrue(data.isMarkingEnabledIn(MediaGroup.TYPE_HOME));
        assertTrue(data.isMarkingEnabledIn(NO_SECTION));
    }

    /**
     * Its own default doesn't change the one of the other filters
     */
    @Test
    public void otherFiltersKeepTheirDefaults() {
        getData();

        assertEquals(ShowsData.MODE_SHOW, ShowsData.instance(RuntimeEnvironment.getApplication()).getMode());
    }

    /**
     * AppPrefs outlives the test, so do the saved values
     */
    private static void clearSaved() {
        TestAccounts.clearSaved(getPrefs(), MembersData.class.getSimpleName());
        TestAccounts.clearSaved(getPrefs(), ShowsData.class.getSimpleName());
        MembersData.resetInstanceForTesting();
        ShowsData.resetInstanceForTesting();
    }

    private static AppPrefs getPrefs() {
        return AppPrefs.instance(RuntimeEnvironment.getApplication());
    }

    private static MembersData getData() {
        return MembersData.instance(RuntimeEnvironment.getApplication());
    }
}
