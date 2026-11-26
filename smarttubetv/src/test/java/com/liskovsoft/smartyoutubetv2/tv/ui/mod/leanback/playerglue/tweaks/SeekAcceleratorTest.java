package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tweaks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SeekAcceleratorTest {
    private static final long INTERVAL_MS = 10_000;
    /**
     * A held key repeats first after about half a second, then about twenty times a second
     */
    private static final long REPEAT_TIMEOUT_MS = 500;
    private static final long REPEAT_DELAY_MS = 50;
    /**
     * The uptime when the seek starts
     */
    private static final long T0 = 7_654_321;
    private long mNowMs;
    private final SeekAccelerator mAccelerator = new SeekAccelerator(() -> mNowMs);

    /**
     * A seek that stays open between the presses: with "seek confirm" on the release of left/right doesn't end it, and nothing
     * ends the one an accessibility action starts.
     */
    @Test
    public void presses_neverSpeedUp() {
        for (long atMs = T0; atMs <= T0 + 5_000; atMs += 300) {
            assertEquals(INTERVAL_MS, press(atMs));
        }
    }

    @Test
    public void hold_speedsUpAfterEachSecond() {
        assertEquals(INTERVAL_MS, press(T0));

        for (long atMs = T0 + REPEAT_TIMEOUT_MS; atMs < T0 + 1_000; atMs += REPEAT_DELAY_MS) {
            assertEquals(INTERVAL_MS, repeat(atMs));
        }

        for (long atMs = T0 + 1_000; atMs < T0 + 2_000; atMs += REPEAT_DELAY_MS) {
            assertEquals(15_000, repeat(atMs));
        }

        for (long atMs = T0 + 2_000; atMs < T0 + 3_000; atMs += REPEAT_DELAY_MS) {
            assertEquals(22_500, repeat(atMs));
        }

        assertEquals(33_750, repeat(T0 + 3_000));
    }

    @Test
    public void pressAfterAHold_startsAgainFromTheInterval() {
        assertEquals(22_500, hold(T0, 2_100));

        assertEquals(INTERVAL_MS, press(T0 + 2_400));
    }

    /**
     * Not from the start of the seek: a tap shortly before the hold would speed it up too early
     */
    @Test
    public void hold_countsItsSecondsFromItsOwnPress() {
        press(T0);

        assertEquals(INTERVAL_MS, hold(T0 + 400, 950));
        assertEquals(15_000, repeat(T0 + 400 + 1_000));
    }

    /**
     * Two seconds or more after the start of the seek the step used to stay as it was until the seek ended
     */
    @Test
    public void holdAfterAPause_speedsUpAgain() {
        press(T0);

        assertEquals(15_000, hold(T0 + 3_000, 1_000));
        assertEquals(22_500, repeat(T0 + 3_000 + 2_000));
    }

    /**
     * E.g. the app was busy for two seconds while the key was held
     */
    @Test
    public void lateRepeat_stillSpeedsUp() {
        press(T0);

        assertEquals(15_000, repeat(T0 + 2_500));
        assertEquals(15_000, repeat(T0 + 3_450));
        assertEquals(22_500, repeat(T0 + 3_500));
    }

    /**
     * The seek ended (e.g. the controls reappeared) under a key that is still held: its next repeat starts a new one
     */
    @Test
    public void reset_startsAgainFromTheIntervalUnderAHeldKey() {
        assertEquals(15_000, hold(T0, 1_000));

        mAccelerator.reset();

        assertEquals(INTERVAL_MS, repeat(T0 + 1_050));
        assertEquals(INTERVAL_MS, repeat(T0 + 2_000));
        assertEquals(15_000, repeat(T0 + 2_050));
    }

    @Test
    public void longHold_stopsBeforeAPositionOverflows() {
        long dayMs = 24 * 60 * 60 * 1_000L;
        long step = press(T0);

        for (int second = 1; second <= 200; second++) {
            long next = repeat(T0 + second * 1_000L);
            assertTrue("Never smaller than before: " + next, next >= step);
            step = next;
        }

        assertEquals(SeekAccelerator.MAX_STEP_MS, step);
        assertTrue("A day plus the step overflows", dayMs + step > dayMs);
    }

    /**
     * A key press at this time, or a step that no key made
     */
    private long press(long atMs) {
        mNowMs = atMs;
        return mAccelerator.nextStepMs(INTERVAL_MS, false);
    }

    /**
     * The auto repeat of a held key at this time
     */
    private long repeat(long atMs) {
        mNowMs = atMs;
        return mAccelerator.nextStepMs(INTERVAL_MS, true);
    }

    /**
     * A key pressed at this time and held for this long: the press, then every repeat up to that moment (included)
     *
     * @return the step of the last one
     */
    private long hold(long pressedAtMs, long forMs) {
        long step = press(pressedAtMs);

        for (long atMs = pressedAtMs + REPEAT_TIMEOUT_MS; atMs <= pressedAtMs + forMs; atMs += REPEAT_DELAY_MS) {
            step = repeat(atMs);
        }

        return step;
    }
}
