package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase;

import java.security.GeneralSecurityException;

/**
 * The AI of the settings search: who answers, with which model, and the user's key for it, encrypted (see KeyCipher).<br/>
 * Kept in prefs of their own, which no backup copies (a backup copies the files of Utils.BACKUP_PREFS) and no account syncs
 * (StorageSync): a key stays on this device.
 */
public final class AiSearchData extends SharedPreferencesBase {
    private static final String TAG = AiSearchData.class.getSimpleName();
    private static final String PROVIDER = "provider";
    private static final String KEY_PREFIX = "key_";
    private static final String MODEL_PREFIX = "model_";
    @SuppressLint("StaticFieldLeak")
    private static AiSearchData sInstance;
    private static AiSearchCodec sTestCodec;
    private final AiSearchCodec mCodec;

    private AiSearchData(Context context, AiSearchCodec codec) {
        super(context.getApplicationContext(), AiSearchData.class.getName());
        mCodec = codec;
    }

    public static AiSearchData instance(Context context) {
        if (sInstance == null) {
            sInstance = new AiSearchData(context, sTestCodec != null ? sTestCodec : new KeyCipher());
        }

        return sInstance;
    }

    @VisibleForTesting
    public static void setTestCodec(@Nullable AiSearchCodec codec) {
        sTestCodec = codec;
        sInstance = null;
    }

    /**
     * @return null when the AI is off
     */
    @Nullable
    public AiProvider getProvider() {
        return AiProvider.fromId(getString(PROVIDER, null));
    }

    public void setProvider(@Nullable AiProvider provider) {
        putString(PROVIDER, provider != null ? provider.id : null);
    }

    /**
     * The one picked, or the provider's default: null when there's none (OpenAI before one is picked)
     */
    @Nullable
    public String getModel(AiProvider provider) {
        String model = getString(MODEL_PREFIX + provider.id, null);
        return !TextUtils.isEmpty(model) ? model : provider.defaultModel;
    }

    public void setModel(AiProvider provider, @Nullable String model) {
        putString(MODEL_PREFIX + provider.id, model);
    }

    public boolean hasKey(AiProvider provider) {
        return getKey(provider) != null;
    }

    /**
     * @return null when there's none, or when it can't be read here anymore (then it's removed: e.g. the prefs came from another
     * device, or the app was reinstalled)
     */
    @Nullable
    public String getKey(AiProvider provider) {
        String encrypted = getString(KEY_PREFIX + provider.id, null);

        if (TextUtils.isEmpty(encrypted)) {
            return null;
        }

        try {
            return mCodec.decrypt(encrypted);
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.e(TAG, "Can't read the key of " + provider.id + ": " + e);
            putString(KEY_PREFIX + provider.id, null);
            return null;
        }
    }

    /**
     * @return false when it can't be encrypted: not kept
     */
    public boolean setKey(AiProvider provider, @Nullable String key) {
        if (TextUtils.isEmpty(key)) {
            putString(KEY_PREFIX + provider.id, null);
            return true;
        }

        try {
            putString(KEY_PREFIX + provider.id, mCodec.encrypt(key));
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            Log.e(TAG, "Can't encrypt the key of " + provider.id + ": " + e);
            return false;
        }
    }

    /**
     * @return the provider picked when it has a key and a model: the AI answers. Null otherwise.
     */
    @Nullable
    public AiProvider getReadyProvider() {
        AiProvider provider = getProvider();
        return provider != null && getModel(provider) != null && hasKey(provider) ? provider : null;
    }
}
