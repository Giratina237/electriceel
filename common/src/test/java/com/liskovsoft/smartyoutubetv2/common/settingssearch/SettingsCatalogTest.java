package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import android.app.Application;

import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

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
    /**
     * Every card is clicked, and nothing shows: no screen starts, no dialog is left to show, no message
     */
    @Test
    public void build_showsNothing() {
        Application app = RuntimeEnvironment.getApplication();

        SettingsCatalog catalog = SettingsCatalog.build(app);

        assertNull(Shadows.shadowOf(app).getNextStartedActivity());
        assertEquals(0, ShadowToast.shownToastCount());
        assertTrue(AppDialogPresenter.instance(app).isEmpty());
        assertTrue("Only " + catalog.getEntries().size(), catalog.getEntries().size() > 150);
    }

    @Test
    public void build_idsAreUnique() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());
        Set<String> ids = new HashSet<>();

        for (SettingEntry entry : catalog.getEntries()) {
            assertTrue(entry.id, ids.add(entry.id));
            assertNotNull(catalog.find(entry.id));
        }
    }

    @Test
    public void search_findsSubtitles() {
        SettingsCatalog catalog = SettingsCatalog.build(RuntimeEnvironment.getApplication());

        List<SettingEntry> found = SettingsSearch.find(catalog.getEntries(), "subtitles", 10);

        assertFalse(found.isEmpty());
        assertTrue(found.get(0).toString(), found.get(0).toString().toLowerCase().contains("subtitle"));
    }

    /**
     * The settings are sent, the texts that aren't the app's aren't
     */
    @Test
    public void prompt_onlyTheAppsTexts() {
        Application app = RuntimeEnvironment.getApplication();
        SettingsCatalog catalog = SettingsCatalog.build(app);
        AiPrompt prompt = new AiPrompt(app);

        String system = prompt.getSystem(catalog.getEntries());
        System.out.println("Settings: " + catalog.getEntries().size() + ", prompt: " + system.length() + " chars");

        assertTrue(prompt.isAllowed(app.getString(com.liskovsoft.smartyoutubetv2.common.R.string.find_setting)));
        assertTrue(prompt.isAllowed("720p"));
        assertTrue(prompt.isAllowed("1.25x"));
        assertFalse(prompt.isAllowed("my-server.example.com"));
        assertFalse(prompt.isAllowed("John's Account"));
        assertFalse(prompt.isAllowed("jdoe@gmail.com"));
        assertFalse(prompt.isAllowed("192.168.0.3"));
        assertTrue(prompt.isAllowed("German"));
        assertTrue(prompt.isAllowed("0.16Mbps, opus, DRC"));
        assertTrue(system.contains("| "));
    }
}
