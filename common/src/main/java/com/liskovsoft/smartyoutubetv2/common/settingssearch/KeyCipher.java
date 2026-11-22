package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypts the AI key of the settings search with a key of the Android Keystore, which never leaves the device: a copy of the
 * prefs (e.g. a backup made by Android) can't be read anywhere else, not even on this device after the app is reinstalled.
 */
final class KeyCipher implements AiSearchCodec {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "ai_settings_search";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    @Override
    public String encrypt(String text) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getKey(true));
        byte[] data = cipher.doFinal(text.getBytes(StandardCharsets.UTF_8));

        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
    }

    @Override
    public String decrypt(String encrypted) throws GeneralSecurityException {
        int split = encrypted.indexOf(':');

        if (split <= 0) {
            throw new GeneralSecurityException("Not encrypted here");
        }

        SecretKey key = getKey(false);

        if (key == null) {
            throw new GeneralSecurityException("No key");
        }

        try {
            byte[] iv = Base64.decode(encrypted.substring(0, split), Base64.NO_WRAP);
            byte[] data = Base64.decode(encrypted.substring(split + 1), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static SecretKey getKey(boolean create) throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);

        try {
            keyStore.load(null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }

        if (keyStore.containsAlias(ALIAS)) {
            return (SecretKey) keyStore.getKey(ALIAS, null);
        }

        if (!create) {
            return null;
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());

        return generator.generateKey();
    }
}
