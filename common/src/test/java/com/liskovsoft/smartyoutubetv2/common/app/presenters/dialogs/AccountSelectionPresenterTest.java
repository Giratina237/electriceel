package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * The account the account button of the classic skin switches to: None (no account) comes after the last one, unless it's
 * hidden (User interface > Skins)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AccountSelectionPresenterTest {
    private static final Account FIRST = account("First", false);
    private static final Account SECOND = account("Second", false);
    private static final List<Account> ACCOUNTS = Arrays.asList(FIRST, SECOND);

    @Test
    public void noneAfterTheLastAccount() {
        assertSame(SECOND, AccountSelectionPresenter.getNext(ACCOUNTS, FIRST, false));
        assertNull(AccountSelectionPresenter.getNext(ACCOUNTS, SECOND, false));
        assertSame(FIRST, AccountSelectionPresenter.getNext(ACCOUNTS, null, false));
    }

    @Test
    public void firstAfterTheLastAccountWithNoneHidden() {
        assertSame(SECOND, AccountSelectionPresenter.getNext(ACCOUNTS, FIRST, true));
        assertSame(FIRST, AccountSelectionPresenter.getNext(ACCOUNTS, SECOND, true));
    }

    /**
     * None picked elsewhere (the Accounts settings): the button goes on to the first account
     */
    @Test
    public void firstAfterNoneWithNoneHidden() {
        assertSame(FIRST, AccountSelectionPresenter.getNext(ACCOUNTS, null, true));
    }

    @Test
    public void oneAccountSwitchesWithNone() {
        List<Account> accounts = Collections.singletonList(FIRST);

        assertNull(AccountSelectionPresenter.getNext(accounts, FIRST, false));
        assertSame(FIRST, AccountSelectionPresenter.getNext(accounts, null, false));
    }

    /**
     * Nothing to switch to: the presenter opens the Accounts settings instead
     */
    @Test
    public void oneAccountStaysWithNoneHidden() {
        assertSame(FIRST, AccountSelectionPresenter.getNext(Collections.singletonList(FIRST), FIRST, true));
    }

    @Test
    public void selectedAccount() {
        Account selected = account("Selected", true);

        assertSame(selected, AccountSelectionPresenter.getSelected(Arrays.asList(FIRST, selected)));
    }

    @Test
    public void noSelectedAccountIsNone() {
        assertNull(AccountSelectionPresenter.getSelected(ACCOUNTS));
    }

    private static Account account(String name, boolean isSelected) {
        return new Account() {
            @Override
            public int getId() {
                return name.hashCode();
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getEmail() {
                return null;
            }

            @Override
            public String getAvatarImageUrl() {
                return null;
            }

            @Override
            public boolean isSelected() {
                return isSelected;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }

            @Override
            public String getProfileName() {
                return name;
            }

            @Override
            public boolean hasChannel() {
                return true;
            }

            @Override
            public String getSharedProfileName() {
                return null;
            }
        };
    }
}
