package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.prefs.MembersData;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The videos of this run known to be ones only the channel's members watch: by their cards' badge (see VideoGroup.isHiddenMembers), the
 * video's own metadata, or its formats, which no client gave (see ErrorFixerController). The next video of autoplay is only an id and a
 * title (see MediaItemMetadata.getNextVideo): it's known to be one when a card of it was seen, or once it's been tried.
 */
public final class MembersVideos {
    private static final int MAX_VIDEOS = 500;
    private static final Set<String> sVideoIds = Collections.newSetFromMap(new LinkedHashMap<String, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_VIDEOS;
        }
    });

    private MembersVideos() {
    }

    public static synchronized void add(@Nullable String videoId) {
        if (videoId != null) {
            sVideoIds.add(videoId);
        }
    }

    public static synchronized boolean contains(@Nullable String videoId) {
        return videoId != null && sVideoIds.contains(videoId);
    }

    /**
     * Only the channel's members watch it: its card or its metadata said so, or it's been found out
     */
    public static boolean isMembers(@Nullable Video video) {
        return video != null && (video.membersLabel != null || contains(video.videoId));
    }

    /**
     * Not to be played: the Members setting hides them (see MembersData.isHidingEverywhere)
     */
    public static boolean isHidden(@Nullable Context context, @Nullable Video video) {
        return context != null && isMembers(video) && MembersData.instance(context).isHidingEverywhere();
    }

    @VisibleForTesting
    public static synchronized void resetForTesting() {
        sVideoIds.clear();
    }
}
