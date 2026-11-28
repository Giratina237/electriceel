package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.settings.AiSearchSettingsPresenter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.disposables.Disposable;

/**
 * The AI help of the settings search, when it's on (see AiSearchData.isEnabled): a question asked of the AI the user set up, with
 * the settings each screen declares and their values now (see AiPrompt). Its picks are settings of that list. The AI only finds
 * settings: it changes nothing.
 */
public final class AiSettingsSearch {
    private static final String TAG = AiSettingsSearch.class.getSimpleName();
    private final Context mContext;
    private final Runnable mOnChange;
    @Nullable
    private String mQuestion;
    @Nullable
    private AiProvider mProvider;
    @Nullable
    private SettingsCatalog mCatalog;
    @Nullable
    private Disposable mRequest;
    /**
     * Of the question asked: null while it's asked
     */
    @Nullable
    private AiClient.Answer mAnswer;
    @Nullable
    private String mError;
    /**
     * Of the error: another model might answer
     */
    private boolean mIsModelError;
    /**
     * Asked of it as the one picked was busy: null when it wasn't
     */
    @Nullable
    private String mFallbackModel;

    /**
     * @param onChange run when the answer or an error comes, on the main thread
     */
    public AiSettingsSearch(Context context, Runnable onChange) {
        mContext = context;
        mOnChange = onChange;
    }

    /**
     * The AI help is on, and who answers is set up
     */
    public boolean isReady() {
        return AiSearchData.instance(mContext).getReadyProvider() != null;
    }

    /**
     * Asks it, the settings listed again first: the AI is told their values as they are now
     *
     * @return false when the AI help is off, or not set up: nothing asked
     */
    public boolean ask(String question) {
        cancel();
        AiProvider provider = AiSearchData.instance(mContext).getReadyProvider();

        if (provider == null || question.trim().isEmpty()) {
            return false;
        }

        SettingsCatalog.clearCache();

        try {
            mCatalog = SettingsCatalog.get(mContext);
        } catch (RuntimeException e) {
            Log.e(TAG, "Can't list the settings: " + e);
            return false;
        }

        mQuestion = question.trim();
        mProvider = provider;
        mFallbackModel = null;
        startRequest(null);

        return true;
    }

    /**
     * Forgets the question: the request stopped
     */
    public void cancel() {
        RxHelper.disposeActions(mRequest);
        mRequest = null;
        mQuestion = null;
        mProvider = null;
        mAnswer = null;
        mError = null;
        mIsModelError = false;
    }

    /**
     * A question was asked: being answered, answered, or failed
     */
    public boolean isActive() {
        return mQuestion != null;
    }

    @Nullable
    public String getQuestion() {
        return mQuestion;
    }

    /**
     * @return who answers, e.g. Claude: null when nothing's asked
     */
    @Nullable
    public String getTitle() {
        return mProvider != null ? mProvider.title : null;
    }

    /**
     * Asking, its reply, or why it didn't answer: null when nothing's asked
     */
    @Nullable
    public String getText() {
        if (mProvider == null) {
            return null;
        }

        if (mError != null) {
            return mError;
        }

        if (mAnswer == null) {
            return mContext.getString(R.string.ai_search_asking, mProvider.title);
        }

        return mAnswer.reply;
    }

    /**
     * It failed with the model: another one can be picked (see pickModel)
     */
    public boolean canPickModel() {
        return mIsModelError && mError != null;
    }

    /**
     * The models of the key, to pick one: the question asked again with it
     */
    public void pickModel() {
        String question = mQuestion;

        if (question == null) {
            return;
        }

        AiSearchSettingsPresenter.instance(mContext).pickModel(() -> {
            if (ask(question)) {
                mOnChange.run();
            }
        });
    }

    /**
     * The settings the AI picked, as they're listed: none until it answers
     */
    public List<SettingEntry> getPicks() {
        if (mAnswer == null || mCatalog == null) {
            return Collections.emptyList();
        }

        List<SettingEntry> picks = new ArrayList<>();

        for (String id : mAnswer.settings) {
            SettingEntry entry = mCatalog.find(id);

            if (entry != null && !picks.contains(entry) && picks.size() < AiPrompt.MAX_PICKS) {
                picks.add(entry);
            }
        }

        return picks;
    }

    /**
     * @param model instead of the one picked, null for it
     */
    private void startRequest(@Nullable String model) {
        AiSearchData data = AiSearchData.instance(mContext);
        AiProvider provider = mProvider;
        String key = provider != null ? data.getKey(provider) : null;

        if (model == null && provider != null) {
            model = data.getModel(provider);
        }

        if (provider == null || key == null || model == null || mCatalog == null) {
            cancel();
            return;
        }

        AiPrompt prompt = new AiPrompt(mContext);
        List<SettingEntry> entries = mCatalog.getEntries();
        String question = mQuestion;
        mRequest = AiClient.ask(provider, model, key, prompt.getSystem(entries), prompt.getUser(entries, question))
                .subscribe(answer -> onAnswer(question, answer), error -> onError(question, error));
    }

    private void onAnswer(String question, AiClient.Answer answer) {
        if (!question.equals(mQuestion)) {
            return;
        }

        mAnswer = answer;
        mOnChange.run();
    }

    private void onError(String question, Throwable error) {
        if (!question.equals(mQuestion)) {
            return;
        }

        Log.e(TAG, "The AI didn't answer: " + error);

        // Busy (e.g. Gemini's 503 "high demand"): asked once more, of another model
        String fallback = getFallbackModel(error);

        if (fallback != null) {
            mFallbackModel = fallback;
            startRequest(fallback);
            return;
        }

        mError = AiSearchSettingsPresenter.getErrorText(mContext, mProvider, error);

        if (error instanceof AiClient.AiException) {
            AiClient.ErrorKind kind = ((AiClient.AiException) error).kind;
            mIsModelError = kind == AiClient.ErrorKind.MODEL || kind == AiClient.ErrorKind.SERVER || kind == AiClient.ErrorKind.LIMIT;
        }

        if (mIsModelError) {
            mError += " " + mContext.getString(R.string.ai_search_press_pick_model);
        }

        mOnChange.run();
    }

    /**
     * @return null when there's none, or it was asked already
     */
    @Nullable
    private String getFallbackModel(Throwable error) {
        if (mFallbackModel != null || mProvider == null || mProvider.fallbackModel == null || !(error instanceof AiClient.AiException)
                || ((AiClient.AiException) error).kind != AiClient.ErrorKind.SERVER) {
            return null;
        }

        String model = AiSearchData.instance(mContext).getModel(mProvider);
        String fallback = mProvider.fallbackModel.equals(model) ? mProvider.defaultModel : mProvider.fallbackModel;

        return fallback != null && !fallback.equals(model) ? fallback : null;
    }
}
