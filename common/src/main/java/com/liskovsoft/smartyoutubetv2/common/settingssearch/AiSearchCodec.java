package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import java.security.GeneralSecurityException;

/**
 * How the AI key is kept in the prefs: encrypted on the device (see KeyCipher), as is in the tests
 */
public interface AiSearchCodec {
    String encrypt(String text) throws GeneralSecurityException;

    /**
     * @throws GeneralSecurityException when it can't be read here (e.g. copied from another device): the key is gone
     */
    String decrypt(String encrypted) throws GeneralSecurityException;
}
