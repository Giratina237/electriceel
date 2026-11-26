package com.liskovsoft.smartyoutubetv2.tv.ui.mod.leanback.playerglue.tweaks;

/**
 * The step of a seek made with the keys: the seek interval of the settings, growing only while a key is held.<br/>
 * A key press starts again from the interval. The repeats of a held key grow the step by 1.5 after each second of holding.
 */
final class SeekAccelerator {
    interface Clock {
        /**
         * A time that only moves forward, e.g. the uptime: the wall clock can be set (by the network) in the middle of a seek
         */
        long nowMs();
    }

    private static final long SPEED_INCREASE_PERIOD_MS = 1000;
    private static final double SPEED_INCREASE_FACTOR = 1.5;
    /**
     * A key held for more than a minute stops here: a position plus or minus the step never overflows
     */
    static final long MAX_STEP_MS = Long.MAX_VALUE / 2;
    private static final long NO_STEP = -1;
    private final Clock mClock;
    private long mStepMs = NO_STEP;
    private long mLastIncreaseMs;

    SeekAccelerator(Clock clock) {
        mClock = clock;
    }

    /**
     * The seek has ended: the next step starts again from the interval, the repeat of a key that is still held too
     */
    void reset() {
        mStepMs = NO_STEP;
    }

    /**
     * @param intervalMs the seek interval of the settings
     * @param isKeyRepeat the step comes from the auto repeat of a held key. False for a key press, and for a step that no
     * key made (e.g. an accessibility action): these are never sped up, however fast they follow each other.
     * @return how far this step seeks
     */
    long nextStepMs(long intervalMs, boolean isKeyRepeat) {
        long nowMs = mClock.nowMs();

        if (!isKeyRepeat || mStepMs == NO_STEP) {
            mStepMs = intervalMs;
            mLastIncreaseMs = nowMs;
        } else if (nowMs - mLastIncreaseMs >= SPEED_INCREASE_PERIOD_MS) {
            // Not only when exactly one period has passed: a repeat that comes late would leave the step as it is for good
            mStepMs = Math.min((long) (mStepMs * SPEED_INCREASE_FACTOR), MAX_STEP_MS);
            mLastIncreaseMs = nowMs;
        }

        return mStepMs;
    }
}
