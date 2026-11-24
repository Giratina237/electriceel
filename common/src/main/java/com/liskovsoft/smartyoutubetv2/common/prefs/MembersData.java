package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.content.Context;

import androidx.annotation.VisibleForTesting;

import com.liskovsoft.sharedutils.helpers.Helpers;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Hides or marks the videos only the channel's members watch, now ("Members only") or before everyone else ("Members first"), found by
 * the style of their badge, which isn't translated (see MediaItem.getMembersLabel). Hidden by default: a video the account can't watch
 * shows the reason on a black screen.<br/>
 * Shown once the account is found to be a member of a channel, any channel (see onMembershipsRead).<br/>
 * Unlike the other filters, it also applies where no section is (the player's suggestions, a playlist page): a hidden one is never
 * played, not even next (see isHidingEverywhere).
 */
public class MembersData extends SectionFilterData {
    // The channels the account was found to be a member of, ever (see onMembershipsRead)
    private static final int MEMBERSHIPS_INDEX = OWN_VALUES_INDEX;
    // They were read once at least: none known is then none, not unknown
    private static final int MEMBERSHIPS_READ_INDEX = OWN_VALUES_INDEX + 1;
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
     * The channels the account is a member of were read (see MembershipManager), while it hides them: any membership shows them all, and
     * sets the mode to Show. Unless Hide was picked: then only a membership not known before does (e.g. a channel joined since), so
     * Hide picked again stays. Hide picked before they were ever read stays too: the ones it's a member of then are known from now on.
     *
     * @return the mode is Show now
     */
    public boolean onMembershipsRead(Collection<String> channelIds) {
        Set<String> known = getMemberships();
        boolean isRead = getBoolean(MEMBERSHIPS_READ_INDEX);
        Set<String> all = new LinkedHashSet<>(known);
        all.addAll(channelIds);
        boolean isNew = all.size() > known.size();
        boolean isShown = isNew && getMode() == MODE_HIDE && (!isModePicked() || isRead);

        if (isNew) {
            setString(MEMBERSHIPS_INDEX, Helpers.mergeList(all));
        }

        if (!isRead) {
            setBoolean(MEMBERSHIPS_READ_INDEX, true);
        }

        if (isShown) {
            setMode(MODE_SHOW);
        }

        return isShown;
    }

    /**
     * The channels the account was found to be a member of, ever (see onMembershipsRead)
     */
    public Set<String> getMemberships() {
        String memberships = getString(MEMBERSHIPS_INDEX, null);

        return memberships != null && !memberships.isEmpty() ?
                new LinkedHashSet<>(Arrays.asList(Helpers.splitArray(memberships))) : Collections.emptySet();
    }

    /**
     * The next instance reads the saved values again
     */
    static void resetInstanceForTesting() {
        sInstance = null;
    }

    /**
     * Forgets the saved values: the next instance has the defaults, no mode picked
     */
    @VisibleForTesting
    public static void clearForTesting(Context context) {
        AppPrefs.instance(context).setProfileData(MembersData.class.getSimpleName(), "");
        sInstance = null;
    }
}
