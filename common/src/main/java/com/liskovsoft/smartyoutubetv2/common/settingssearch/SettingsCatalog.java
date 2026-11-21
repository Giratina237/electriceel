package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionCategory;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.SettingsCardsPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.AppDataSourceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Every setting of the app, for the settings search: the cards of the Settings section, the cards of their sections, and the rows of
 * the settings panels they open. Made from the settings themselves, as they are now: each card is clicked, and what it opens is
 * taken instead of shown (see SettingsCapture). So nothing is listed by hand: a new setting is found as soon as its panel has it.
 * <br/>
 * The lists a row opens (e.g. Video speed) aren't looked through: their rows run as they're clicked. The row itself is found.
 */
public final class SettingsCatalog {
    private static final String TAG = SettingsCatalog.class.getSimpleName();
    /**
     * Sections opened in sections, e.g. Settings, System, General: more than that is a card that opens its own section again
     */
    private static final int MAX_DEPTH = 6;
    private final List<SettingEntry> mEntries;

    private SettingsCatalog(List<SettingEntry> entries) {
        mEntries = Collections.unmodifiableList(entries);
    }

    /**
     * Each card and row once, in the order of the cards (as made, not as sorted on the screen) and of the rows of their panels
     */
    public List<SettingEntry> getEntries() {
        return mEntries;
    }

    @Nullable
    public SettingEntry find(String id) {
        for (SettingEntry entry : mEntries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }

        return null;
    }

    /**
     * On the main thread: the settings panels are made there
     */
    public static SettingsCatalog build(Context context) {
        Builder builder = new Builder(context);
        List<SettingsItem> cards = AppDataSourceManager.instance().getSettingSections(context);

        for (int i = 0; i < cards.size(); i++) {
            builder.addCard(cards.get(i), new int[] {i}, new ArrayList<>());
        }

        return new SettingsCatalog(builder.mEntries);
    }

    /**
     * Opens it as its card would.<br/>
     * A section: on its own screen, the sections before it under it (onScreen is called first: e.g. the results are closed).<br/>
     * A card or a row of its panel: the panel over the dialog shown (the results: Back goes to them), titled as from its section
     * (e.g. "Player - Video"), the row focused.<br/>
     * The cards are found again by their place: they're made in the same order each time.
     *
     * @return false when it isn't there anymore (e.g. the language changed meanwhile): nothing opened
     */
    public static boolean open(Context context, SettingEntry entry, Runnable onScreen) {
        try {
            return openCard(context, entry, onScreen);
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't open " + entry + ": " + e);
            return false;
        }
    }

    private static boolean openCard(Context context, SettingEntry entry, Runnable onScreen) {
        List<SettingsItem> cards = AppDataSourceManager.instance().getSettingSections(context);
        List<SettingsCardsPresenter.Cards> sections = new ArrayList<>();
        List<Integer> openers = new ArrayList<>();
        SettingsItem card = null;

        for (int level = 0; level < entry.cards.length; level++) {
            int index = entry.cards[level];

            if (index >= cards.size() || !TextUtils.equals(cards.get(index).title, getCardTitle(entry, level))) {
                return false;
            }

            card = cards.get(index);

            if (level == entry.cards.length - 1) {
                break;
            }

            SettingsCardsPresenter.Cards section = captureCards(card);

            if (section == null) {
                return false;
            }

            if (!sections.isEmpty()) {
                openers.add(index);
            }

            sections.add(section);
            cards = section.getItems(context);
        }

        if (card == null || card.onClick == null) {
            return false;
        }

        if (entry.kind == SettingEntry.KIND_SECTION) {
            SettingsCardsPresenter.Cards section = captureCards(card);

            if (section == null) {
                return false;
            }

            if (!sections.isEmpty()) {
                openers.add(entry.cards[entry.cards.length - 1]);
            }

            sections.add(section);
            onScreen.run();
            SettingsCardsPresenter.instance(context).showPath(sections, openers, -1);
            return true;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        if (!sections.isEmpty()) {
            dialog.setNextTitlePrefix(sections.get(sections.size() - 1).getTitle(context));
        }

        if (entry.isRow) {
            dialog.setFocusedItemMatcher(entry::matches);
        }

        card.onClick.run();

        return true;
    }

    /**
     * Of the card at the level: the titles of the cards are at the start of the path, a card's own is its title
     */
    private static String getCardTitle(SettingEntry entry, int level) {
        boolean isCard = entry.kind == SettingEntry.KIND_SECTION || entry.kind == SettingEntry.KIND_CARD;
        return isCard && level == entry.cards.length - 1 ? entry.title : entry.path.get(level);
    }

    @Nullable
    private static SettingsCardsPresenter.Cards captureCards(SettingsItem card) {
        return card.onClick != null ? SettingsCapture.capture(card.onClick).cards : null;
    }

    /**
     * @return trimmed, without the icons of the text (e.g. of an account), null when nothing is left
     */
    @Nullable
    static String toText(@Nullable CharSequence text) {
        if (text == null) {
            return null;
        }

        String result = text.toString().replace("￼", "").trim();

        return result.isEmpty() ? null : result;
    }

    private static final class Builder {
        private final Context mContext;
        private final List<SettingEntry> mEntries = new ArrayList<>();

        private Builder(Context context) {
            mContext = context;
        }

        private void addCard(SettingsItem card, int[] cards, List<String> path) {
            String title = toText(card.title);

            if (title == null) {
                return;
            }

            SettingsCapture.Result result = capture(card);
            boolean isSection = result != null && result.cards != null;
            add(isSection ? SettingEntry.KIND_SECTION : SettingEntry.KIND_CARD, title, path, card.getDescription(),
                    Collections.emptyList(), null, false, cards, false, null, null);

            if (result == null) {
                return;
            }

            List<String> inside = plus(path, title);

            if (isSection && cards.length < MAX_DEPTH) {
                List<SettingsItem> items;

                try {
                    items = result.cards.getItems(mContext);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Can't get the cards of " + title + ": " + e);
                    return;
                }

                for (int i = 0; i < items.size(); i++) {
                    addCard(items.get(i), plus(cards, i), inside);
                }
            } else if (result.panel != null) {
                addPanel(result.panel, cards, inside);
            }
        }

        /**
         * @return null when the card failed to open
         */
        @Nullable
        private SettingsCapture.Result capture(SettingsItem card) {
            if (card.onClick == null) {
                return null;
            }

            AppDialogPresenter dialog = AppDialogPresenter.instance(mContext);

            try {
                return SettingsCapture.capture(card.onClick);
            } catch (RuntimeException e) {
                Log.e(TAG, "Can't look through " + card.title + ": " + e);
                return null;
            } finally {
                // Appended but not shown (e.g. it failed meanwhile): not for the next dialog
                dialog.clearPending();
            }
        }

        private void addPanel(List<OptionCategory> categories, int[] cards, List<String> path) {
            for (OptionCategory category : categories) {
                if (category.options == null) {
                    continue;
                }

                String categoryTitle = toText(category.title);

                switch (category.type) {
                    case OptionCategory.TYPE_RADIO_LIST:
                        addChoices(category, categoryTitle, cards, path);
                        break;
                    case OptionCategory.TYPE_CHECKBOX_LIST:
                        addChecks(category, categoryTitle, cards, path);
                        break;
                    case OptionCategory.TYPE_SINGLE_SWITCH:
                    case OptionCategory.TYPE_SINGLE_BUTTON:
                        for (OptionItem item : category.options) {
                            String title = toText(item.getTitle());

                            if (title == null || item.isNote()) {
                                continue;
                            }

                            boolean isSwitch = category.type == OptionCategory.TYPE_SINGLE_SWITCH;
                            add(isSwitch ? SettingEntry.KIND_TOGGLE : SettingEntry.KIND_BUTTON, title, path, toText(item.getDescription()),
                                    Collections.emptyList(), null, isSwitch && item.isSelected(), cards, true, categoryTitle, title);
                        }
                        break;
                    case OptionCategory.TYPE_STRING_LIST:
                    case OptionCategory.TYPE_REORDER_LIST:
                    case OptionCategory.TYPE_LONG_TEXT:
                        // A row that opens a list or a text
                        if (categoryTitle != null) {
                            add(SettingEntry.KIND_BUTTON, categoryTitle, path, null, Collections.emptyList(), null, false, cards, true,
                                    categoryTitle, null);
                        }
                        break;
                    default:
                        // QR codes, chat, comments: nothing to set
                        break;
                }
            }
        }

        private void addChoices(OptionCategory category, @Nullable String categoryTitle, int[] cards, List<String> path) {
            List<String> choices = new ArrayList<>();
            String picked = null;

            for (OptionItem item : category.options) {
                String title = toText(item.getTitle());

                if (title == null || item.isNote() || item.isLabel() || item.isSeparator()) {
                    continue;
                }

                choices.add(title);

                if (picked == null && item.isSelected()) {
                    picked = title;
                }
            }

            // Untitled: the only list of the panel, named as its card
            String title = categoryTitle != null ? categoryTitle : path.get(path.size() - 1);
            add(SettingEntry.KIND_CHOICE, title, path, null, choices, picked, false, cards, true, categoryTitle, null);
        }

        /**
         * Each checkbox under the title of its list, and under its label in the list when it has one
         */
        private void addChecks(OptionCategory category, @Nullable String categoryTitle, int[] cards, List<String> path) {
            List<String> inside = categoryTitle != null ? plus(path, categoryTitle) : path;
            String label = null;

            for (OptionItem item : category.options) {
                String title = toText(item.getTitle());

                if (title == null || item.isSeparator() || item.isNote()) {
                    continue;
                }

                if (item.isLabel()) {
                    label = title;
                    continue;
                }

                boolean isMenu = item.isMenu();
                add(isMenu ? SettingEntry.KIND_BUTTON : SettingEntry.KIND_TOGGLE, title, label != null ? plus(inside, label) : inside,
                        toText(item.getDescription()), Collections.emptyList(), null, !isMenu && item.isSelected(), cards, true,
                        categoryTitle, title);
            }
        }

        private void add(int kind, String title, List<String> path, @Nullable String description, List<String> choices,
                         @Nullable String picked, boolean isOn, int[] cards, boolean isRow, @Nullable String categoryTitle,
                         @Nullable String itemTitle) {
            mEntries.add(new SettingEntry("s" + (mEntries.size() + 1), kind, title, path, toText(description), choices, picked,
                    isOn, cards, isRow, categoryTitle, itemTitle));
        }

        private static List<String> plus(List<String> list, String item) {
            List<String> result = new ArrayList<>(list);
            result.add(item);
            return result;
        }

        private static int[] plus(int[] array, int item) {
            int[] result = Arrays.copyOf(array, array.length + 1);
            result[array.length] = item;
            return result;
        }
    }
}
