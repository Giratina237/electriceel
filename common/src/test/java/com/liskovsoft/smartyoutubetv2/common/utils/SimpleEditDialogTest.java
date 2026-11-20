package com.liskovsoft.smartyoutubetv2.common.utils;

import android.widget.EditText;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * An IPv4 address's field: the keyboard's number pad, and an address typed on it whole
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SimpleEditDialogTest {
    private EditText mField;

    @Before
    public void setUp() {
        mField = new EditText(RuntimeEnvironment.getApplication());
        SimpleEditDialog.setCharacters(mField, SimpleEditDialog.IPV4_CHARACTERS, SimpleEditDialog.IPV4_INPUT_TYPE);
    }

    /**
     * What the keyboard is told: its number pad, with the dot
     */
    @Test
    public void theKeyboardIsTheNumberPad() {
        assertEquals(SimpleEditDialog.IPV4_INPUT_TYPE, mField.getInputType());
    }

    /**
     * The number pad's own filter takes one dot: 192.0.2.1 would end as 192.021
     */
    @Test
    public void anAddressKeepsEachOfItsDots() {
        typeKeys("192.0.2.1");

        assertEquals("192.0.2.1", mField.getText().toString());
    }

    /**
     * Pasted or typed, what isn't in an IPv4 address is left out
     */
    @Test
    public void lettersAndColonsAreLeftOut() {
        mField.getText().append("dns 1.1.1.1: a,b");

        assertEquals("1.1.1.1", mField.getText().toString());
    }

    /**
     * As a keyboard commits them: one at a time, each through the field's filters
     */
    private void typeKeys(String keys) {
        for (char key : keys.toCharArray()) {
            mField.getText().insert(mField.getText().length(), String.valueOf(key));
        }
    }
}
