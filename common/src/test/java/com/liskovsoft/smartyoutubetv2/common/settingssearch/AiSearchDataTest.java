package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.security.GeneralSecurityException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AiSearchDataTest {
    private boolean mBroken;

    @Before
    public void setUp() {
        AiSearchData.setTestCodec(new AiSearchCodec() {
            @Override
            public String encrypt(String text) {
                return "enc:" + text;
            }

            @Override
            public String decrypt(String encrypted) throws GeneralSecurityException {
                if (mBroken) {
                    throw new GeneralSecurityException("Other device");
                }

                return encrypted.substring(4);
            }
        });
    }

    @After
    public void tearDown() {
        AiSearchData.setTestCodec(null);
    }

    @Test
    public void ready_withKeyAndModel() {
        AiSearchData data = AiSearchData.instance(RuntimeEnvironment.getApplication());
        data.setEnabled(true);
        data.setProvider(AiProvider.CLAUDE);
        assertNull(data.getReadyProvider());

        data.setKey(AiProvider.CLAUDE, "sk-ant-x");

        assertSame(AiProvider.CLAUDE, data.getReadyProvider());
        assertEquals("claude-opus-5-5", data.getModel(AiProvider.CLAUDE));
        assertEquals("sk-ant-x", data.getKey(AiProvider.CLAUDE));
    }

    @Test
    public void openAi_needsAModel() {
        AiSearchData data = AiSearchData.instance(RuntimeEnvironment.getApplication());
        data.setEnabled(true);
        data.setProvider(AiProvider.OPENAI);
        data.setKey(AiProvider.OPENAI, "sk-x");
        assertNull(data.getReadyProvider());

        data.setModel(AiProvider.OPENAI, "gpt-5.5");
        assertSame(AiProvider.OPENAI, data.getReadyProvider());
    }

    /**
     * The AI help is off until it's turned on: set up, it isn't asked then
     */
    @Test
    public void off_untilTurnedOn() {
        AiSearchData data = AiSearchData.instance(RuntimeEnvironment.getApplication());
        data.setProvider(AiProvider.CLAUDE);
        data.setKey(AiProvider.CLAUDE, "sk-ant-x");

        assertFalse(data.isEnabled());
        assertNull(data.getReadyProvider());

        data.setEnabled(true);
        assertSame(AiProvider.CLAUDE, data.getReadyProvider());

        data.setEnabled(false);
        assertNull(data.getReadyProvider());
    }

    /**
     * Copied from elsewhere: can't be read here, so it's gone
     */
    @Test
    public void unreadableKey_removed() {
        AiSearchData data = AiSearchData.instance(RuntimeEnvironment.getApplication());
        data.setKey(AiProvider.GEMINI, "AIza");
        mBroken = true;

        assertFalse(data.hasKey(AiProvider.GEMINI));
        mBroken = false;
        assertFalse(data.hasKey(AiProvider.GEMINI));
        assertTrue(data.setKey(AiProvider.GEMINI, "AIza"));
    }
}
