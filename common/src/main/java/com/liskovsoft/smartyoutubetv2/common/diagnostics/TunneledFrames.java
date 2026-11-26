package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import androidx.annotation.Nullable;

/**
 * Where a tunneled video decoder is at against the position (see DebugInfoMediaCodecVideoRenderer#getTunneledFrames): it shows the
 * frames itself, so only what it says it showed is known. A picture that stands still while the position moves is told apart by it:
 * frames given ahead and not shown (the decoder found them late and dropped them), or none given.
 */
public final class TunneledFrames {
    /**
     * The position less the time of the last frame the decoder said it showed: how far behind the picture is, null when it said none
     * since the renderer was enabled
     */
    @Nullable
    public final Long shownBehindMs;
    /**
     * Since it said so, null when it said none
     */
    @Nullable
    public final Long shownAgoMs;
    /**
     * The time of the last sample given to the decoder less the position: what it has to show, null when none was given since its
     * codec was flushed (e.g. a seek)
     */
    @Nullable
    public final Long queuedAheadMs;
    /**
     * The position it was last sent to (a seek, or where the renderer was enabled) less the time of the first sample given to the
     * decoder since: the keyframe before it, how far the decoder had to decode to get there before it showed a frame. Null when none
     * was given since. A start sent to a position that froze and one that didn't are told apart by it (H5B64KBE, Y1D66CNW: 4 of 7
     * froze).
     */
    @Nullable
    public final Long keyframeBehindMs;

    public TunneledFrames(@Nullable Long shownBehindMs, @Nullable Long shownAgoMs, @Nullable Long queuedAheadMs,
                          @Nullable Long keyframeBehindMs) {
        this.shownBehindMs = shownBehindMs;
        this.shownAgoMs = shownAgoMs;
        this.queuedAheadMs = queuedAheadMs;
        this.keyframeBehindMs = keyframeBehindMs;
    }

    /**
     * As a failure's details: tunneled, tunneledShownBehindMs, tunneledShownAgoMs, tunneledQueuedAheadMs, tunneledKeyframeBehindMs
     */
    void addTo(PlayerFailure failure) {
        failure.detail("tunneled", true)
                .detail("tunneledShownBehindMs", shownBehindMs)
                .detail("tunneledShownAgoMs", shownAgoMs)
                .detail("tunneledQueuedAheadMs", queuedAheadMs)
                .detail("tunneledKeyframeBehindMs", keyframeBehindMs);
    }
}
