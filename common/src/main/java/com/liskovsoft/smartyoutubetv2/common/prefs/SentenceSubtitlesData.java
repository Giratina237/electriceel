package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataSaverBase;

/**
 * Subtitles one sentence at a time (see SentenceSubtitles). Off by default.<br/>
 * Each account has its own value.
 */
public class SentenceSubtitlesData extends DataSaverBase {
    // Storage layout. Don't change: the values are saved by index.
    private static final int ENABLED_INDEX = 0;
    private static SentenceSubtitlesData sInstance;

    private SentenceSubtitlesData(Context context) {
        // Saved right away: a save still pending when the account changes is dropped
        super(context, true, true);
    }

    public static SentenceSubtitlesData instance(Context context) {
        if (sInstance == null) {
            sInstance = new SentenceSubtitlesData(context);
        }

        return sInstance;
    }

    public boolean isEnabled() {
        return getBoolean(ENABLED_INDEX);
    }

    public void setEnabled(boolean enable) {
        setBoolean(ENABLED_INDEX, enable);
    }
}
