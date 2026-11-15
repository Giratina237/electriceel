package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;

/**
 * Hides or marks the videos only the channel's members watch, now ("Members only") or before everyone else ("Members first"), found by
 * the style of their badge, which isn't translated (see MediaItem.getMembersLabel). Hidden by default: a video the account can't watch
 * shows the reason on a black screen.<br/>
 * Unlike the other filters, it also applies where no section is (the player's suggestions, a playlist page): a hidden one is never
 * played, not even next (see isHidingEverywhere).
 */
public class MembersData extends SectionFilterData {
    private static MembersData sInstance;

    private MembersData(Context context) {
        super(context);
    }

    public static MembersData instance(Context context) {
        if (sInstance == null) {
            sInstance = new MembersData(context);
        }

        return sInstance;
    }

    @Override
    protected int getDefaultMode() {
        return MODE_HIDE;
    }

    /**
     * Green, as YouTube's members' badge
     */
    @Override
    protected int getDefaultMarkColor() {
        return AiSListFilterData.MARK_COLOR_GREEN;
    }

    /**
     * Hidden from the group: in a section, as its setting says; where no section is (-1, e.g. the player's suggestions), whenever the
     * mode is Hide and the quick toggle isn't off
     *
     * @param sectionId the section of the group (see VideoGroup.getFilterSectionId), -1 for none
     */
    public boolean isHidingEnabledIn(int sectionId) {
        return sectionId == -1 ? isHidingEverywhere() : isHidingEnabled(sectionId);
    }

    /**
     * Marked in the group, when it's not hidden (see isHidingEnabledIn)
     */
    public boolean isMarkingEnabledIn(int sectionId) {
        return sectionId == -1 ? getMode() != MODE_SHOW && !isHidingEverywhere() : isMarkingEnabled(sectionId);
    }

    /**
     * A hidden one isn't played: next, by autoplay (see SuggestionsController.getNext), or after it's found out to be one (its formats,
     * its metadata)
     */
    public boolean isHidingEverywhere() {
        return getMode() == MODE_HIDE && !isHidingPaused();
    }

    /**
     * The next instance reads the saved values again
     */
    static void resetInstanceForTesting() {
        sInstance = null;
    }
}
