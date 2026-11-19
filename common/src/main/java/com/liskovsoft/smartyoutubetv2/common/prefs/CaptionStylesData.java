package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;
import android.view.accessibility.CaptioningManager;

import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataSaverBase;

/**
 * Captions in the colors, italics and places their author gave them (see StyledCaptions), e.g. a color per speaker (issue #95).<br/>
 * Until it's switched, it's off in general but on for captions that have them: it reads as on while Android's captions are on,
 * or while the captions shown have them.<br/>
 * Each account has its own value.
 */
public class CaptionStylesData extends DataSaverBase {
    // Storage layout. Don't change: the values are saved by index.
    private static final int MODE_INDEX = 0;
    private static final int MODE_DEFAULT = 0;
    private static final int MODE_ON = 1;
    private static final int MODE_OFF = 2;
    private static CaptionStylesData sInstance;
    private final Context mContext;

    private CaptionStylesData(Context context) {
        // Saved right away: a save still pending when the account changes is dropped
        super(context, true, true);
        mContext = context.getApplicationContext();
    }

    public static CaptionStylesData instance(Context context) {
        if (sInstance == null) {
            sInstance = new CaptionStylesData(context);
        }

        return sInstance;
    }

    /**
     * Whether the captions that have them are shown with them: unless switched off
     */
    public boolean isApplied() {
        return getInt(MODE_INDEX, MODE_DEFAULT) != MODE_OFF;
    }

    /**
     * As the switch reads
     *
     * @param isShownStyled the captions shown now have them (see StyledCaptions#isTrackStyled)
     */
    public boolean isEnabled(boolean isShownStyled) {
        int mode = getInt(MODE_INDEX, MODE_DEFAULT);

        if (mode != MODE_DEFAULT) {
            return mode == MODE_ON;
        }

        return isShownStyled || isSystemCaptionsEnabled();
    }

    public void setEnabled(boolean enable) {
        setInt(MODE_INDEX, enable ? MODE_ON : MODE_OFF);
    }

    /**
     * Android's own captions setting (Accessibility, Captions)
     */
    private boolean isSystemCaptionsEnabled() {
        CaptioningManager captioningManager = (CaptioningManager) mContext.getSystemService(Context.CAPTIONING_SERVICE);

        return captioningManager != null && captioningManager.isEnabled();
    }

    /**
     * The next instance reads the saved values again
     */
    static void resetInstanceForTesting() {
        sInstance = null;
    }
}
