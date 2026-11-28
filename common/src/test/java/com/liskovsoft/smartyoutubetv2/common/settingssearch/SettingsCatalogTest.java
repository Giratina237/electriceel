package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.BackupSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsCatalogTest {
    @Test
    public void build_findsTheSettings() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());

        assertTrue("Only " + catalog.getEntries().size(), catalog.getEntries().size() > 150);
    }

    /**
     * Read from what the cards declare: no panel is made by its presenter (e.g. Backup isn't held, its auto-backups wait while it
     * is), no screen started, no message
     */
    @Test
    public void build_opensNothing() {
        Application app = RuntimeEnvironment.getApplication();

        SettingsCatalog.build(app);

        assertFalse(BackupSettingsPresenter.hasInstance());
        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
        assertTrue(AppDialogPresenter.instance(app).isEmpty());
    }

    /**
     * Each card declares what it opens, a section or a panel, so the search finds what's in it. Not the two whose rows are loaded
     * in the background: Accounts, Android channels.
     */
    @Test
    public void cards_declareWhatTheyOpen() {
        Application app = RuntimeEnvironment.getApplication();
        Set<String> loaded = new HashSet<>();
        loaded.add(app.getString(R.string.settings_accounts));
        loaded.add(app.getString(R.string.home_screen_channels));
        List<String> missing = new ArrayList<>();

        checkCards(app, AppDataSourceManager.instance().getSettingItems(app), loaded, missing);

        assertTrue(missing.toString(), missing.isEmpty());
    }

    private static void checkCards(Application app, List<SettingsItem> cards, Set<String> loaded, List<String> missing) {
        for (SettingsItem card : cards) {
            if (card.getCards() != null) {
                checkCards(app, card.getCards().getItems(app), loaded, missing);
            } else if (card.getPanel() == null && !loaded.contains(card.title)) {
                missing.add(card.title);
            }
        }
    }

    /**
     * Not the rows of a menu greyed out in its panel: Categories, without a key of the Data API, can't be opened
     */
    @Test
    public void build_notInGreyedOutMenus() {
        Application app = RuntimeEnvironment.getApplication();
        SettingsCatalog catalog = SettingsCatalog.build(app);
        String categories = app.getString(R.string.hide_content_categories);
        boolean isListed = false;

        for (SettingEntry entry : catalog.getEntries()) {
            assertFalse(entry.toString(), categories.equals(entry.menuItemTitle));
            isListed |= categories.equals(entry.title);
        }

        // The row itself is
        assertTrue(isListed);
    }

    @Test
    public void search_findsSubtitles() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());

        List<SettingEntry> found = SettingsSearch.find(catalog.getEntries(), "subtitles", 10);

        assertFalse(found.isEmpty());
        assertTrue(found.get(0).toString(), found.get(0).toString().toLowerCase().contains("subtitle"));
    }

    /**
     * The words of the question, in any order and any case, the ones about no setting left out: the setting with all of them first
     */
    @Test
    public void search_matchesTheWords() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());
        List<SettingEntry> entries = catalog.getEntries();

        List<SettingEntry> shorts = SettingsSearch.find(entries, "how do I HIDE the shorts", 50);
        assertFalse(shorts.isEmpty());
        assertTrue(shorts.get(0).toString(), shorts.get(0).title.toLowerCase().contains("shorts"));

        assertFalse(SettingsSearch.find(entries, "shorts hide", 50).isEmpty());
        assertTrue(SettingsSearch.find(entries, "zzqx", 50).isEmpty());

        // Music isn't a setting's name: a choice of Set-up sections
        assertFalse(SettingsSearch.find(entries, "music", 50).isEmpty());
    }

    /**
     * A question of words about no setting alone is searched all the same
     */
    @Test
    public void search_onlyCommonWords() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());

        assertFalse(SettingsSearch.find(catalog.getEntries(), "like", 10).isEmpty());
    }

    /**
     * What the settings do is kept, as they're shown under them: its words are searched too (see SettingsSearch.Fields)
     */
    @Test
    public void build_keepsTheDescriptions() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());
        boolean hasDescription = false;

        for (SettingEntry entry : catalog.getEntries()) {
            if (entry.description != null && !entry.description.isEmpty()) {
                hasDescription = true;
                break;
            }
        }

        assertTrue(hasDescription);
    }

    /**
     * In the menu a row of a panel opens: Hide content, then Shorts
     */
    @Test
    public void build_rowsOfMenus() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());
        SettingEntry everywhere = null;

        for (SettingEntry entry : catalog.getEntries()) {
            if (entry.title.equals("Hide shorts everywhere")) {
                everywhere = entry;
            }
        }

        assertNotNull(everywhere);
        assertEquals("Shorts", everywhere.menuItemTitle);
        assertEquals(SettingEntry.KIND_TOGGLE, everywhere.kind);
        assertEquals("Hide shorts everywhere", SettingsSearch.find(catalog.getEntries(), "hide shorts everywhere", 3).get(0).title);
    }

    /**
     * The items of the list a button opens to put in order: Settings isn't one of the Sidebar order, it's always last
     */
    @Test
    public void build_itemsOfOrderLists() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());
        SettingEntry order = null;

        for (SettingEntry entry : catalog.getEntries()) {
            if (entry.title.equals("Order of the sidebar")) {
                order = entry;
            }
        }

        assertNotNull(order);
        assertFalse(order.choices.isEmpty());
        assertTrue(order.choices.toString(), order.choices.contains("Home"));
        assertFalse(order.choices.contains("Settings"));
    }

    /**
     * The rows of the list a button opens: Music, in Set-up sections of the Sidebar
     */
    @Test
    public void build_rowsOfButtonLists() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());
        SettingEntry music = null;

        for (SettingEntry entry : catalog.getEntries()) {
            if (entry.title.equals("Music") && entry.path.contains("Set-up sections")) {
                music = entry;
            }
        }

        assertNotNull(music);
        assertEquals("Set-up sections", music.menuItemTitle);
        assertEquals(SettingEntry.KIND_TOGGLE, music.kind);
    }
}
