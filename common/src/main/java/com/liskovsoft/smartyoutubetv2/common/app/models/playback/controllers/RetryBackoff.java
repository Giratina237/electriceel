package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;

/**
 * The waits before something that failed the same way is tried again, for one key (e.g. a video): the first, then twice the one before
 * up to the longest, till it's reset or another key comes. A retry a second later, every time, ran 40 engine restarts a minute for 10
 * minutes (FARAJV5R #17–#35), and 55 fetches a minute for 15 (#47–#99).
 */
final class RetryBackoff {
    private final long mFirstMs;
    private final long mMaxMs;
    @Nullable
    private String mKey;
    private int mCount;

    RetryBackoff(long firstMs, long maxMs) {
        mFirstMs = firstMs;
        mMaxMs = maxMs;
    }

    /**
     * The wait before the next try for the key: the first's for another key than the last
     */
    long next(@Nullable String key) {
        if (!Helpers.equals(key, mKey)) {
            mKey = key;
            mCount = 0;
        }

        long delayMs = mFirstMs << Math.min(mCount, 16);
        mCount++;

        return Math.min(delayMs, mMaxMs);
    }

    /**
     * The waits given for the key so far
     */
    int getCount(@Nullable String key) {
        return Helpers.equals(key, mKey) ? mCount : 0;
    }

    void reset() {
        mKey = null;
        mCount = 0;
    }
}
