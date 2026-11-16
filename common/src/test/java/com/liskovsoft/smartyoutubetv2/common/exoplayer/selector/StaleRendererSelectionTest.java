package com.liskovsoft.smartyoutubetv2.common.exoplayer.selector;

import android.util.Pair;

import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection.Definition;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * FARAJV5R #4: after a SABR reload, the video's selection was made with tracks of the source before (two groups, vp9 and avc) in the
 * new one's one group: ArrayIndexOutOfBoundsException, length=1, index=1. The tracks are the new groups'.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StaleRendererSelectionTest {
    private TrackSelectorManager mManager;

    @Before
    public void setUp() {
        mManager = new TrackSelectorManager(RuntimeEnvironment.getApplication());
    }

    @Test
    public void otherGroupsAreSelectedFromTheirOwnTracks() {
        Format vp9 = video("247", MimeTypes.VIDEO_VP9, "vp9");
        Format avc = video("136", MimeTypes.VIDEO_H264, "avc1.4d401f");
        mManager.selectTrack(savedAvc());

        Pair<Definition, MediaTrack> before = mManager.onSelectVideoTrack(
                new TrackGroupArray(new TrackGroup(vp9), new TrackGroup(avc)), DefaultTrackSelector.Parameters.DEFAULT);

        assertNotNull(before);
        assertSame(avc, before.second.format);

        // The source after the reload: one group, avc again
        Format reloaded = video("136", MimeTypes.VIDEO_H264, "avc1.4d401f");
        Pair<Definition, MediaTrack> after = mManager.onSelectVideoTrack(new TrackGroupArray(new TrackGroup(reloaded)),
                DefaultTrackSelector.Parameters.DEFAULT);

        assertNotNull(after);
        assertSame(reloaded, after.second.format);
    }

    /**
     * The track selected before isn't in the new groups: none of its own, the player's default (FARAJV5R #4's shape: index 1 of 1)
     */
    @Test
    public void aTrackNotInTheNewGroupsIsNoSelection() {
        Format vp9 = video("247", MimeTypes.VIDEO_VP9, "vp9");
        Format avc = video("136", MimeTypes.VIDEO_H264, "avc1.4d401f");
        mManager.selectTrack(savedAvc());
        mManager.onSelectVideoTrack(new TrackGroupArray(new TrackGroup(vp9), new TrackGroup(avc)), DefaultTrackSelector.Parameters.DEFAULT);

        Pair<Definition, MediaTrack> after = mManager.onSelectVideoTrack(
                new TrackGroupArray(new TrackGroup(video("247", MimeTypes.VIDEO_VP9, "vp9"))), DefaultTrackSelector.Parameters.DEFAULT);

        assertNull(after);
    }

    /**
     * Fake format restored from the saved preferences
     */
    private static MediaTrack savedAvc() {
        MediaTrack track = ExoFormatItem.fromVideoSpec("1280,720,30,avc", false).getTrack();
        track.isSaved = true;
        return track;
    }

    private static Format video(String id, String mimeType, String codecs) {
        return Format.createVideoSampleFormat(id, mimeType, codecs, 2_000_000, Format.NO_VALUE, 1280, 720, 30, null, null);
    }
}
