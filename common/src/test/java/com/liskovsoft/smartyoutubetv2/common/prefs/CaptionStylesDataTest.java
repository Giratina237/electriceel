package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import android.view.accessibility.CaptioningManager;

import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Off in general but on for captions that have colors, italics or places of their own, or while Android's captions are on, until
 * it's switched (issue #95)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CaptionStylesDataTest {
    @Before
    public void setUp() {
        getPrefs().onAccountChanged(null); // the anonymous account
        clearSaved();
        setSystemCaptionsEnabled(false);
    }

    @After
    public void tearDown() {
        Utils.sHandler.removeCallbacksAndMessages(null);
        getPrefs().onAccountChanged(null);
        clearSaved();
        setSystemCaptionsEnabled(false);
    }

    @Test
    public void unswitched_isOnlyOnForStyledCaptions() {
        CaptionStylesData data = getData();

        assertTrue(data.isApplied());
        assertFalse(data.isEnabled(false));
        assertTrue(data.isEnabled(true));
    }

    @Test
    public void unswitched_isOnWhileAndroidsCaptionsAreOn() {
        setSystemCaptionsEnabled(true);

        assertTrue(getData().isEnabled(false));
    }

    @Test
    public void switchedOff_isOffEverywhere() {
        CaptionStylesData data = getData();
        setSystemCaptionsEnabled(true);

        data.setEnabled(false);

        assertFalse(data.isApplied());
        assertFalse(data.isEnabled(true));
        assertFalse(data.isEnabled(false));
    }

    @Test
    public void switchedOn_isOnEverywhere() {
        CaptionStylesData data = getData();
        data.setEnabled(false);

        data.setEnabled(true);

        assertTrue(data.isApplied());
        assertTrue(data.isEnabled(false));
        assertTrue(data.isEnabled(true));
    }

    @Test
    public void eachAccountHasItsOwnValue() {
        AppPrefs prefs = getPrefs();
        CaptionStylesData data = getData();

        prefs.onAccountChanged(TestAccounts.FIRST);
        data.setEnabled(false);
        ShadowLooper.shadowMainLooper().idle(); // saved before the account changes

        prefs.onAccountChanged(TestAccounts.SECOND);

        // Starts from the default, not the other account's
        assertTrue(data.isApplied());

        prefs.onAccountChanged(TestAccounts.FIRST);

        assertFalse(data.isApplied());
    }

    private static void setSystemCaptionsEnabled(boolean enabled) {
        CaptioningManager captioningManager =
                (CaptioningManager) RuntimeEnvironment.getApplication().getSystemService(Context.CAPTIONING_SERVICE);
        Shadows.shadowOf(captioningManager).setEnabled(enabled);
    }

    private static void clearSaved() {
        TestAccounts.clearSaved(getPrefs(), CaptionStylesData.class.getSimpleName());
        CaptionStylesData.resetInstanceForTesting();
    }

    private static AppPrefs getPrefs() {
        return AppPrefs.instance(RuntimeEnvironment.getApplication());
    }

    private static CaptionStylesData getData() {
        return CaptionStylesData.instance(RuntimeEnvironment.getApplication());
    }
}
