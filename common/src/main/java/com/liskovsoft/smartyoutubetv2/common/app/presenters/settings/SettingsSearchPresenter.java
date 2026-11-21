package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.AiClient;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.AiPrompt;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.AiProvider;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.AiSearchData;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingEntry;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingsCatalog;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.SettingsSearch;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.disposables.Disposable;

/**
 * The "Find a setting" card of the Settings section: a question, then the settings that answer it, as rows that open them (the
 * panel with the row focused, see SettingsCatalog.open). Found on the device at once (SettingsSearch), and by the AI the user
 * set up (AiSearchSettingsPresenter), whose picks come first once it answers. The AI only finds settings: it changes nothing.
 */
public class SettingsSearchPresenter extends BasePresenter<Void> {
    private static final String TAG = SettingsSearchPresenter.class.getSimpleName();
    public static final int SEARCH_ID = 153;
    private static final int MAX_MATCHES = 25;
    @SuppressLint("StaticFieldLeak")
    private static SettingsSearchPresenter sInstance;
    private String mQuestion;
    private SettingsCatalog mCatalog;
    private List<SettingEntry> mMatches;
    private AiProvider mProvider;
    private Disposable mRequest;
    /**
     * Of the question asked: null while it's asked
     */
    @Nullable
    private AiClient.Answer mAnswer;
    @Nullable
    private String mError;
    /**
     * The row of the AI in the results shown: its text changes as the answer comes
     */
    private OptionItem mAiRow;

    private SettingsSearchPresenter(Context context) {
        super(context);
    }

    public static SettingsSearchPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new SettingsSearchPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public void show() {
        askQuestion(null);
    }

    /**
     * @param previous shown again when the question is cancelled
     */
    private void askQuestion(@Nullable String previous) {
        Context context = getContext();

        if (context == null) {
            return;
        }

        boolean[] isSearched = {false};
        SimpleEditDialog.show(context, context.getString(R.string.find_setting), context.getString(R.string.find_setting_hint),
                previous != null ? previous : "", question -> {
                    if (question.trim().isEmpty()) {
                        return false;
                    }

                    isSearched[0] = true;
                    search(question.trim());
                    return true;
                }, () -> {
                    if (!isSearched[0] && previous != null && mQuestion != null) {
                        // The request stopped as the results closed
                        if (mProvider != null && mAnswer == null && mError == null) {
                            startRequest(context);
                        }

                        showResults();
                    }
                });
    }

    private void search(String question) {
        Context context = getContext();

        if (context == null) {
            return;
        }

        disposeRequest();
        mQuestion = question;
        mAnswer = null;
        mError = null;

        try {
            mCatalog = SettingsCatalog.build(context);
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't list the settings: " + e);
            MessageHelpers.showMessage(context, e.getMessage());
            return;
        }

        mMatches = SettingsSearch.find(mCatalog.getEntries(), question, MAX_MATCHES);
        mProvider = AiSearchData.instance(context).getReadyProvider();

        if (mProvider != null) {
            startRequest(context);
        }

        showResults();
    }

    private void startRequest(Context context) {
        AiSearchData data = AiSearchData.instance(context);
        String key = data.getKey(mProvider);
        String model = data.getModel(mProvider);

        if (key == null || model == null) {
            mProvider = null;
            return;
        }

        AiPrompt prompt = new AiPrompt(context);
        List<SettingEntry> entries = mCatalog.getEntries();
        String question = mQuestion;
        mRequest = AiClient.ask(mProvider, model, key, prompt.getSystem(entries), prompt.getUser(entries, question))
                .subscribe(answer -> onAnswer(question, answer), error -> onError(question, error));
    }

    private void onAnswer(String question, AiClient.Answer answer) {
        if (!question.equals(mQuestion)) {
            return;
        }

        mAnswer = answer;
        updateResults();
    }

    private void onError(String question, Throwable error) {
        if (!question.equals(mQuestion)) {
            return;
        }

        Log.e(TAG, "The AI didn't answer: " + error);
        Context context = getContext();

        if (context == null) {
            return;
        }

        mError = AiSearchSettingsPresenter.getErrorText(context, mProvider, error);
        updateResults();
    }

    /**
     * The results built again when they're the dialog shown: the AI's picks first, focused. When a setting is open over them,
     * their AI row says the answer came, and pressing it shows it.
     */
    private void updateResults() {
        Context context = getContext();

        if (context == null) {
            return;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);

        if (dialog.isDialogShown() && dialog.getId() == SEARCH_ID) {
            showResults(true);
        } else if (mAiRow instanceof UiOptionItem && mProvider != null) {
            ((UiOptionItem) mAiRow).setDescription(context.getString(R.string.ai_search_answered, mProvider.title));
        }
    }

    private void showResults() {
        showResults(false);
    }

    private void showResults(boolean replace) {
        Context context = getContext();

        if (context == null || mQuestion == null || mCatalog == null) {
            return;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        OptionItem focused = null;

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.find_setting_new), mQuestion, option -> {
            disposeRequest();
            // The text field is behind the dialog otherwise, and the dialog keeps the keys
            dialog.closeDialog();
            askQuestion(mQuestion);
        }));

        List<SettingEntry> picks = new ArrayList<>();

        if (mProvider != null) {
            mAiRow = UiOptionItem.from(mProvider.title, getAiText(context), option -> {
                if (mAnswer != null || mError != null) {
                    showResults(true);
                }
            });
            dialog.appendSingleButton(mAiRow);

            if (mAnswer != null) {
                for (String id : mAnswer.settings) {
                    SettingEntry entry = mCatalog.find(id);

                    if (entry != null && !picks.contains(entry) && picks.size() < AiPrompt.MAX_PICKS) {
                        picks.add(entry);
                    }
                }
            }
        } else {
            mAiRow = null;
            dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.ai_search_setup),
                    context.getString(R.string.ai_search_setup_desc),
                    option -> AiSearchSettingsPresenter.instance(context).show()));
        }

        for (SettingEntry entry : picks) {
            OptionItem row = createRow(context, entry);
            dialog.appendSingleButton(row);

            if (focused == null) {
                focused = row;
            }
        }

        List<SettingEntry> matches = new ArrayList<>(mMatches);
        matches.removeAll(picks);

        if (matches.isEmpty() && picks.isEmpty() && (mProvider == null || mAnswer != null)) {
            dialog.appendSingleButton(note(context.getString(R.string.find_setting_none, mQuestion)));
        } else if (!matches.isEmpty()) {
            dialog.appendSingleButton(note(context.getString(picks.isEmpty() && mProvider == null ?
                    R.string.find_setting_matches : R.string.find_setting_more)));

            for (SettingEntry entry : matches) {
                OptionItem row = createRow(context, entry);
                dialog.appendSingleButton(row);

                // Without the AI: the best match
                if (focused == null && mProvider == null) {
                    focused = row;
                }
            }
        }

        if (focused != null) {
            dialog.setFocusedItem(focused);
        }

        dialog.enableExpandable(false);
        dialog.enableReplace(replace);
        dialog.setId(SEARCH_ID);
        dialog.showDialog(context.getString(R.string.find_setting), this::disposeRequest);
    }

    private OptionItem createRow(Context context, SettingEntry entry) {
        String where = TextUtils.join(" › ", entry.path);

        if (entry.kind == SettingEntry.KIND_CHOICE && entry.picked != null) {
            where = where.isEmpty() ? entry.picked : where + " · " + entry.picked;
        }

        return UiOptionItem.from(entry.title, where, option -> {
            AppDialogPresenter dialog = AppDialogPresenter.instance(context);

            if (!SettingsCatalog.open(context, entry, dialog::closeDialog)) {
                MessageHelpers.showMessage(context, R.string.find_setting_gone);
            }
        });
    }

    private static OptionItem note(String text) {
        UiOptionItem item = (UiOptionItem) UiOptionItem.from(text);
        item.setNote(true);
        return item;
    }

    private String getAiText(Context context) {
        if (mError != null) {
            return mError;
        }

        if (mAnswer == null) {
            return context.getString(R.string.ai_search_asking, mProvider.title);
        }

        return mAnswer.reply;
    }

    private void disposeRequest() {
        RxHelper.disposeActions(mRequest);
        mRequest = null;
    }
}
