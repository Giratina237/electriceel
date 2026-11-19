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

    public TunneledFrames(@Nullable Long shownBehindMs, @Nullable Long shownAgoMs, @Nullable Long queuedAheadMs) {
        this.shownBehindMs = shownBehindMs;
        this.shownAgoMs = shownAgoMs;
        this.queuedAheadMs = queuedAheadMs;
    }

    /**
     * As a failure's details: tunneled, tunneledShownBehindMs, tunneledShownAgoMs, tunneledQueuedAheadMs
     */
    void addTo(PlayerFailure failure) {
        failure.detail("tunneled", true)
                .detail("tunneledShownBehindMs", shownBehindMs)
                .detail("tunneledShownAgoMs", shownAgoMs)
                .detail("tunneledQueuedAheadMs", queuedAheadMs);
    }
}
