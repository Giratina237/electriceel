package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tunneling turned off for the run when its decoder froze the picture, and for good on a device where it did in two runs, till the
 * user sets the setting again (Z55WD4E0, 0J159Z0Z: the same freeze 14 minutes apart, an app restart between them)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TunnelingFallbackTest {
    @Before
    public void setUp() {
        TunnelingFallback.reset(getContext());
        TunnelingFallback.newRunForTesting();
    }

    @After
    public void tearDown() {
        TunnelingFallback.reset(getContext());
        TunnelingFallback.newRunForTesting();
    }

    @Test
    public void turnedOffItsOffForTheRunOnly() {
        assertTrue(TunnelingFallback.turnOff(getContext()));

        assertTrue(TunnelingFallback.isOff(getContext()));
        assertFalse(TunnelingFallback.isSuspended(getContext()));

        TunnelingFallback.newRunForTesting();

        assertFalse(TunnelingFallback.isOff(getContext()));
    }

    @Test
    public void turnedOffInTwoRunsItsSuspendedInTheNextOnes() {
        TunnelingFallback.turnOff(getContext());
        TunnelingFallback.newRunForTesting();
        TunnelingFallback.turnOff(getContext());

        assertTrue(TunnelingFallback.isSuspended(getContext()));

        TunnelingFallback.newRunForTesting();

        assertTrue(TunnelingFallback.isOff(getContext()));
        // Already off: nothing a restart would change
        assertFalse(TunnelingFallback.turnOff(getContext()));
    }

    @Test
    public void aRunCountsOnce() {
        TunnelingFallback.turnOff(getContext());
        TunnelingFallback.turnOff(getContext());

        assertFalse(TunnelingFallback.isSuspended(getContext()));
    }

    /**
     * The user set the setting again: tried anew, the runs counted from none
     */
    @Test
    public void aResetTriesItAgain() {
        TunnelingFallback.turnOff(getContext());
        TunnelingFallback.newRunForTesting();
        TunnelingFallback.turnOff(getContext());

        TunnelingFallback.reset(getContext());

        assertFalse(TunnelingFallback.isOff(getContext()));
        TunnelingFallback.turnOff(getContext());
        assertFalse(TunnelingFallback.isSuspended(getContext()));
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
