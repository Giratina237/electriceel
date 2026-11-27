package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SettingsCardsPresenter;

import java.util.ArrayList;
import java.util.List;

/**
 * The settings looked through without showing them, for the settings search (see SettingsCatalog): a card is clicked as usual,
 * and the cards or the settings panel it would open are handed over here instead. Nothing is shown and no screen starts
 * (see AppDialogPresenter.showDialog, SettingsCardsPresenter.show and ViewManager.startView).<br/>
 * A panel that loads what it shows in the background (e.g. Accounts) isn't looked through: it checks {@link #isActive()} and
 * shows nothing. The card that opens it is found all the same.<br/>
 * What a panel looked through holds is let go afterwards, as if it was closed: what's passed to {@link #release} for its close
 * (e.g. Backup lets its auto-backups run again only then, see BackupSettingsPresenter.unhold). Its other close callbacks don't
 * run: they apply what was changed in it (e.g. Search clears the search history), and nothing was.<br/>
 * On the main thread only.
 */
public final class SettingsCapture {
    private static Result sResult;

    /**
     * What a card opened: the cards of a section, or a settings panel. Nothing when it opens neither (e.g. a panel loaded in the
     * background).
     */
    static final class Result {
        SettingsCardsPresenter.Cards cards;
        CharSequence panelTitle;
        List<OptionCategory> panel;
        final List<Runnable> onClose = new ArrayList<>();

        boolean isEmpty() {
            return cards == null && panel == null;
        }
    }

    private SettingsCapture() {
    }

    /**
     * The settings are being looked through: show nothing, start nothing
     */
    public static boolean isActive() {
        return sResult != null;
    }

    /**
     * @param open e.g. the click of a card
     * @return what it opened, empty when it opened nothing
     */
    static Result capture(Runnable open) {
        Result previous = sResult;
        Result result = new Result();
        sResult = result;

        try {
            open.run();
        } finally {
            try {
                // Let go as it was held, still looked through
                for (Runnable onClose : result.onClose) {
                    onClose.run();
                }
            } finally {
                sResult = previous;
            }
        }

        return result;
    }

    /**
     * From SettingsCardsPresenter.show
     *
     * @return taken: the cards aren't shown
     */
    public static boolean offerCards(SettingsCardsPresenter.Cards cards) {
        if (sResult == null) {
            return false;
        }

        // The first one: a card opens one thing
        if (sResult.isEmpty()) {
            sResult.cards = cards;
        }

        return true;
    }

    /**
     * From AppDialogPresenter.showDialog
     *
     * @param categories the rows of the panel, as appended to it
     * @return taken: the panel isn't shown
     */
    public static boolean offerPanel(CharSequence title, List<OptionCategory> categories) {
        if (sResult == null) {
            return false;
        }

        if (sResult.isEmpty()) {
            sResult.panelTitle = title;
            sResult.panel = new ArrayList<>(categories);
        }

        return true;
    }

    /**
     * The close callback of a panel that lets go of what the panel holds while it's open (e.g. an instance held, a listener): run
     * also when the panel is only looked through. As it is otherwise.
     */
    public static Runnable release(Runnable onClose) {
        return new Release(onClose);
    }

    /**
     * From AppDialogPresenter: the close callback of a panel looked through, run after it's looked through when it's a
     * {@link #release}
     *
     * @return taken: it doesn't run as a dialog closes
     */
    public static boolean offerOnClose(Runnable onClose) {
        if (sResult == null) {
            return false;
        }

        if (onClose instanceof Release) {
            sResult.onClose.add(onClose);
        }

        return true;
    }

    private static final class Release implements Runnable {
        private final Runnable mOnClose;

        private Release(Runnable onClose) {
            mOnClose = onClose;
        }

        @Override
        public void run() {
            mOnClose.run();
        }
    }
}
