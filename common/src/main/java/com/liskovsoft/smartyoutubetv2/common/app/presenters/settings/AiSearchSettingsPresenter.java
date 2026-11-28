package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.annotation.SuppressLint;
import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.SettingsRows;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.AiClient;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.AiProvider;
import com.liskovsoft.smartyoutubetv2.common.settingssearch.AiSearchData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.disposables.Disposable;

/**
 * The AI help of the settings search (see SettingsSearchBar): on or off, who answers, the user's key for it (checked as it's
 * entered, kept encrypted on the device) and the model.
 */
public class AiSearchSettingsPresenter extends BasePresenter<Void> {
    private static final int AI_SETTINGS_ID = 154;
    @SuppressLint("StaticFieldLeak")
    private static AiSearchSettingsPresenter sInstance;
    private final AiSearchData mData;
    private Disposable mAction;
    private UiOptionItem mKeyRow;
    private UiOptionItem mModelRow;
    /**
     * Run once a model is picked from the list (e.g. the question asked again with it)
     */
    @Nullable
    private Runnable mOnModelPicked;

    private AiSearchSettingsPresenter(Context context) {
        super(context);
        mData = AiSearchData.instance(context);
    }

    public static AiSearchSettingsPresenter instance(Context context) {
        if (sInstance == null) {
            sInstance = new AiSearchSettingsPresenter(context);
        }

        sInstance.setContext(context);

        return sInstance;
    }

    public void show() {
        show(false);
    }

    /**
     * @param pickModel the models are listed at once (e.g. OpenAI, once its key is entered: it has no default model)
     */
    private void show(boolean pickModel) {
        Context context = getContext();

        if (context == null) {
            return;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        dialog.setId(AI_SETTINGS_ID);
        dialog.showPanel(context.getString(R.string.ai_search), this::appendRows, () -> RxHelper.disposeActions(mAction));

        if (pickModel) {
            pickModel();
        }
    }

    /**
     * The rows of the panel show shows (see SettingsPanel)
     */
    public void appendRows(SettingsRows dialog) {
        Context context = getContext();

        if (context == null) {
            return;
        }

        AiProvider current = mData.getProvider();

        dialog.appendSingleSwitch(UiOptionItem.from(context.getString(R.string.ai_search_enabled),
                context.getString(R.string.ai_search_enabled_desc), option -> {
                    mData.setEnabled(option.isSelected());

                    if (option.isSelected() && mData.getReadyProvider() == null) {
                        MessageHelpers.showMessage(context, R.string.ai_search_enabled_not_ready);
                    }
                }, mData.isEnabled()));

        List<OptionItem> providers = new ArrayList<>();

        for (AiProvider provider : AiProvider.values()) {
            providers.add(UiOptionItem.from(provider.title, option -> setProvider(provider), provider == current));
        }

        dialog.appendRadioCategory(context.getString(R.string.ai_search_provider), providers);

        mKeyRow = (UiOptionItem) UiOptionItem.from(context.getString(R.string.ai_search_key), getKeyText(context),
                option -> enterKey());
        dialog.appendSingleButton(mKeyRow);

        mModelRow = (UiOptionItem) UiOptionItem.from(context.getString(R.string.ai_search_model), getModelText(context),
                option -> pickModel());
        dialog.appendSingleButton(mModelRow);

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.ai_search_key_remove), option -> {
            AiProvider provider = mData.getProvider();

            if (provider != null) {
                mData.setKey(provider, null);
                updateRows();
            }
        }));

        dialog.appendSingleButton(UiOptionItem.from(context.getString(R.string.ai_search_get_key), option -> showKeyPage()));

        dialog.appendLongTextCategory(context.getString(R.string.ai_search_about),
                UiOptionItem.from(context.getString(R.string.ai_search_privacy)));
    }

    private void setProvider(@Nullable AiProvider provider) {
        mData.setProvider(provider);
        updateRows();
    }

    private void updateRows() {
        Context context = getContext();

        if (context == null) {
            return;
        }

        if (mKeyRow != null) {
            mKeyRow.setDescription(getKeyText(context));
        }

        if (mModelRow != null) {
            mModelRow.setDescription(getModelText(context));
        }
    }

    private String getKeyText(Context context) {
        AiProvider provider = mData.getProvider();

        if (provider == null) {
            return "—";
        }

        return provider.title + ": " + context.getString(mData.hasKey(provider) ? R.string.ai_search_key_set : R.string.ai_search_key_not_set);
    }

    private String getModelText(Context context) {
        AiProvider provider = mData.getProvider();

        if (provider == null) {
            return "—";
        }

        String model = mData.getModel(provider);
        return model != null ? model : context.getString(R.string.ai_search_key_not_set);
    }

    /**
     * The provider picked, or null and a message to pick one
     */
    @Nullable
    private AiProvider getPickedProvider(Context context) {
        AiProvider provider = mData.getProvider();

        if (provider == null) {
            MessageHelpers.showMessage(context, R.string.ai_search_pick_provider);
        }

        return provider;
    }

    private void enterKey() {
        Context context = getContext();

        if (context == null) {
            return;
        }

        AiProvider provider = getPickedProvider(context);

        if (provider == null) {
            return;
        }

        // The text field is behind the panel otherwise, and the panel keeps the keys. Opened again once the key is checked.
        AppDialogPresenter.instance(context).closeDialog();
        boolean[] isEntered = {false};
        SimpleEditDialog.showPassword(context, context.getString(R.string.ai_search_key_enter, provider.title), "", newValue -> {
            String key = newValue.trim();

            if (key.isEmpty()) {
                return false;
            }

            isEntered[0] = true;
            checkKey(provider, key);
            return true;
        }, () -> {
            if (!isEntered[0]) {
                show();
            }
        });
    }

    private void checkKey(AiProvider provider, String key) {
        Context context = getContext();

        if (context == null) {
            return;
        }

        MessageHelpers.showMessage(context, R.string.ai_search_key_checking);
        RxHelper.disposeActions(mAction);
        mAction = AiClient.listModels(provider, key).subscribe(models -> {
            if (!mData.setKey(provider, key)) {
                MessageHelpers.showMessage(context, R.string.ai_error_save_key);
                show();
                return;
            }

            MessageHelpers.showMessage(context, R.string.ai_search_key_saved);
            show(mData.getModel(provider) == null);
        }, error -> {
            MessageHelpers.showMessage(context, getErrorText(context, provider, error));
            show();
        });
    }

    private void pickModel() {
        pickModel(null);
    }

    /**
     * The models the key can use, to pick one: over the dialog shown
     *
     * @param onPicked run once one is picked from the list
     */
    public void pickModel(@Nullable Runnable onPicked) {
        mOnModelPicked = onPicked;
        Context context = getContext();

        if (context == null) {
            return;
        }

        AiProvider provider = getPickedProvider(context);

        if (provider == null) {
            return;
        }

        String key = mData.getKey(provider);

        if (key == null) {
            enterKey();
            return;
        }

        MessageHelpers.showMessage(context, R.string.ai_search_models_loading);
        RxHelper.disposeActions(mAction);
        mAction = AiClient.listModels(provider, key).subscribe(
                models -> showModels(provider, models),
                error -> MessageHelpers.showMessage(context, getErrorText(context, provider, error)));
    }

    private void showModels(AiProvider provider, List<AiClient.Model> models) {
        Context context = getContext();

        if (context == null) {
            return;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        String current = mData.getModel(provider);
        List<OptionItem> items = new ArrayList<>();

        for (AiClient.Model model : models) {
            String title = model.title.equals(model.id) ? model.id : model.title + " (" + model.id + ")";
            items.add(UiOptionItem.from(title, option -> {
                mData.setModel(provider, model.id);
                updateRows();
                dialog.goBack();
                Runnable onPicked = mOnModelPicked;
                mOnModelPicked = null;

                if (onPicked != null) {
                    onPicked.run();
                }
            }, model.id.equals(current)));
        }

        items.add(UiOptionItem.from(context.getString(R.string.ai_search_model_other), option -> enterModel(provider), false));

        dialog.appendRadioCategory(context.getString(R.string.ai_search_model), items);
        dialog.showDialog(provider.title + " - " + context.getString(R.string.ai_search_model));
    }

    private void enterModel(AiProvider provider) {
        Context context = getContext();

        if (context == null) {
            return;
        }

        AppDialogPresenter.instance(context).closeDialog();
        String current = mData.getModel(provider);
        SimpleEditDialog.show(context, context.getString(R.string.ai_search_model_id), context.getString(R.string.ai_search_model_id),
                current != null ? current : "", newValue -> {
                    mData.setModel(provider, newValue.trim());
                    return true;
                }, this::show);
    }

    private void showKeyPage() {
        Context context = getContext();

        if (context == null) {
            return;
        }

        AiProvider provider = getPickedProvider(context);

        if (provider == null) {
            return;
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(context);
        dialog.appendQrCode(provider.keyPageUrl, provider.keyPageUrl);
        dialog.showDialog(context.getString(R.string.ai_search_key_enter, provider.title));
    }

    /**
     * Why the AI didn't answer, for the user
     */
    public static String getErrorText(Context context, @Nullable AiProvider provider, Throwable error) {
        String title = provider != null ? provider.title : "AI";

        if (!(error instanceof AiClient.AiException)) {
            return context.getString(R.string.ai_error_bad_answer, title);
        }

        switch (((AiClient.AiException) error).kind) {
            case KEY_REFUSED:
                return context.getString(R.string.ai_error_key, title);
            case LIMIT:
                return context.getString(R.string.ai_error_limit, title);
            case NETWORK:
                return context.getString(R.string.ai_error_network, title);
            case DECLINED:
                return context.getString(R.string.ai_error_declined, title);
            case MODEL:
                return context.getString(R.string.ai_error_model, title);
            case SERVER:
                return context.getString(R.string.ai_error_server, title);
            default:
                return context.getString(R.string.ai_error_bad_answer, title);
        }
    }
}
