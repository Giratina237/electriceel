package com.liskovsoft.smartyoutubetv2.common.prefs;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Hide None (User interface > Skins): the account button of the classic skin never switches to no account. Kept for the
 * device, with the other account settings.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AccountsDataTest {
    private static final String ACCOUNTS_DATA = "accounts_data";

    @Before
    public void setUp() throws Exception {
        getPrefs().setData(ACCOUNTS_DATA, "");
        restart();
    }

    @After
    public void tearDown() throws Exception {
        getPrefs().setData(ACCOUNTS_DATA, "");
        restart();
        Utils.sHandler.removeCallbacksAndMessages(null);
    }

    @Test
    public void noneShownByDefault() {
        assertFalse(getData().isNoneAccountHidden());
    }

    @Test
    public void hiddenNoneKeptAfterRestart() throws Exception {
        getData().setNoneAccountHidden(true);
        restart();

        assertTrue(getData().isNoneAccountHidden());
    }

    @Test
    public void shownNoneKeptAfterRestart() throws Exception {
        getData().setNoneAccountHidden(true);
        getData().setNoneAccountHidden(false);
        restart();

        assertFalse(getData().isNoneAccountHidden());
    }

    /**
     * Its own field: the account selection on boot is kept apart
     */
    @Test
    public void selectionOnBootKeptApart() throws Exception {
        getData().selectAccountOnBoot(true);
        getData().setNoneAccountHidden(true);
        getData().selectAccountOnBoot(false);
        restart();

        assertTrue(getData().isNoneAccountHidden());
        assertFalse(getData().isSelectAccountOnBootEnabled());
    }

    /**
     * Saved by a version without it: the selection on boot, the two unused fields and the passwords (none)
     */
    @Test
    public void noneShownWithOldData() throws Exception {
        getPrefs().setData(ACCOUNTS_DATA, Helpers.mergeData(true, null, null, Helpers.mergeArray()));
        restart();

        assertFalse(getData().isNoneAccountHidden());
        assertTrue(getData().isSelectAccountOnBootEnabled());
    }

    /**
     * A new instance restores the saved data, as when the app starts. The old one stops listening for account changes.
     */
    private static void restart() throws Exception {
        Field field = AccountsData.class.getDeclaredField("sInstance");
        field.setAccessible(true);
        AccountsData old = (AccountsData) field.get(null);

        if (old != null) {
            MediaServiceManager.instance().removeAccountListener(old);
        }

        field.set(null, null);
    }

    private static AppPrefs getPrefs() {
        return AppPrefs.instance(RuntimeEnvironment.getApplication());
    }

    private static AccountsData getData() {
        return AccountsData.instance(RuntimeEnvironment.getApplication());
    }
}
