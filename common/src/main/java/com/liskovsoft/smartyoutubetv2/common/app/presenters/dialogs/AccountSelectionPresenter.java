package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.mediaserviceinterfaces.SignInService;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AccountSettingsPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.prefs.AccountsData;
import com.liskovsoft.smartyoutubetv2.common.utils.GlideIconFetcher;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

public class AccountSelectionPresenter extends BasePresenter<Void> {
    private static final String TAG = AccountSelectionPresenter.class.getSimpleName();
    @SuppressLint("StaticFieldLeak")
    private static AccountSelectionPresenter sInstance;
    private final SignInService mSignInService;

    public AccountSelectionPresenter(Context context) {
        super(context);
        ServiceManager service = YouTubeServiceManager.instance();
        mSignInService = service.getSignInService();
    }

    public static AccountSelectionPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new AccountSelectionPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public void show() {
        show(false);
    }

    public void show(boolean force) {
        if (!AccountsData.instance(getContext()).isSelectAccountOnBootEnabled() && !force) {
            // user don't want to see selection dialog
            return;
        }

        GlideIconFetcher.fetchDrawables(getContext(),
                Helpers.map(mSignInService.getAccounts(), Account::getAvatarImageUrl),
                icons -> createAndShowDialog(mSignInService.getAccounts(), icons, force));
    }

    public void nextAccountOrDialog() {
        MediaServiceManager.instance().loadAccounts(this::nextAccountOrDialog);
    }

    public void unhold() {
        sInstance = null;
    }

    private void createAndShowDialog(List<Account> accounts, List<Drawable> icons, boolean force) {
        if (accounts.size() <= 1 && !force) {
            return;
        }

        AppDialogPresenter dialogPresenter = AppDialogPresenter.instance(getContext());

        appendAccountSelection(accounts, icons, dialogPresenter);

        dialogPresenter.showDialog(getContext().getString(R.string.settings_accounts), this::unhold);
    }

    private void appendAccountSelection(List<Account> accounts, List<Drawable> icons, AppDialogPresenter settingsPresenter) {
        List<OptionItem> optionItems = new ArrayList<>();

        optionItems.add(UiOptionItem.from(
                getContext().getString(R.string.dialog_account_none), optionItem -> {
                    selectAccount(null);
                    settingsPresenter.closeDialog();
                }, true
        ));

        int index = -1;

        for (Account account : accounts) {
            index++;
            CharSequence icon = Utils.icon(accounts.size() == icons.size() ? icons.get(index) : null);
            optionItems.add(UiOptionItem.from(
                    TextUtils.concat(icon, " ", formatAccount(account)), option -> {
                        selectAccount(account);
                        settingsPresenter.closeDialog();
                    }, account.isSelected()
            ));
        }

        settingsPresenter.appendRadioCategory(getContext().getString(R.string.dialog_account_list), optionItems);
    }

    private void nextAccountOrDialog(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            AccountSettingsPresenter.instance(getContext()).show();
            return;
        }

        Account current = getSelected(accounts);
        Account next = getNext(accounts, current, AccountsData.instance(getContext()).isNoneAccountHidden());

        // One account and None hidden: nothing to switch to
        if (next == current) {
            AccountSettingsPresenter.instance(getContext()).show();
            return;
        }

        selectAccount(next);
    }

    /**
     * @return null for None (no account)
     */
    @Nullable
    static Account getSelected(List<Account> accounts) {
        for (Account account : accounts) {
            if (account.isSelected()) {
                return account;
            }
        }

        return null;
    }

    /**
     * The account the account button switches to: the one after the current, None (null) after the last, the first after None.
     * With None hidden, the first after the last.
     *
     * @param current null for None
     */
    @Nullable
    static Account getNext(List<Account> accounts, @Nullable Account current, boolean isNoneHidden) {
        int nextIndex = accounts.indexOf(current) + 1;

        if (nextIndex < accounts.size()) {
            return accounts.get(nextIndex);
        }

        return isNoneHidden ? accounts.get(0) : null;
    }

    public void selectAccount(Account account) {
        mSignInService.selectAccount(account);
        Utils.updateChannels(getContext());
    }

    private String formatAccount(Account account) {
        String format;

        if (account.getEmail() != null) {
            format = String.format("%s (%s)", account.getName(), account.getEmail());
        } else {
            format = account.getName();
        }

        return format;
    }
}
