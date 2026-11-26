package com.liskovsoft.smartyoutubetv2.common.exoplayer.errors;

import androidx.annotation.Nullable;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer.DecoderInitializationException;
import com.google.android.exoplayer2.source.DefaultMediaSourceEventListener;
import com.google.android.exoplayer2.source.MediaSource.MediaPeriodId;
import com.google.android.exoplayer2.source.chunk.Chunk;
import com.google.android.exoplayer2.source.chunk.ContainerMediaChunk;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.util.MimeTypes;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.diagnostics.PlayerFailure;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorManager;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.TrackSelectorUtil;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.MediaTrack;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class TrackErrorFixer extends DefaultMediaSourceEventListener {
    private static final int BLACKLIST_CHECK_MS = 1_000;
    private static final int BLACKLIST_CLEAR_MS = 10_000;
    private static final String TAG = TrackErrorFixer.class.getSimpleName();
    private final TrackSelectorManager mTrackSelectorManager;
    private long mSelectionTimeMs;
    private final Map<MediaTrack, Long> mBlacklistedTracks = new HashMap<>();
    private OnHttpError mOnHttpError;
    @Nullable
    private LiveCheck mLiveCheck;

    /**
     * The video playing is a live stream
     */
    public interface LiveCheck {
        boolean isLive();
    }

    public interface OnHttpError {
        /**
         * A segment of a track failed with the HTTP code
         *
         * @param track audio, video, text (subtitles) or other (see #trackOf)
         * @param host the server's domain (see PlayerFailure#describeHost), null when not known
         * @param failed the format of the track in use, when it's audio or video
         * @param switched another codec is selected instead
         */
        void onHttpError(int responseCode, String track, @Nullable String host, @Nullable Format failed, boolean switched);
    }

    public TrackErrorFixer(TrackSelectorManager trackSelectorManager) {
        mTrackSelectorManager = trackSelectorManager;
    }

    public void setOnHttpError(OnHttpError listener) {
        mOnHttpError = listener;
    }

    public void setLiveCheck(@Nullable LiveCheck liveCheck) {
        mLiveCheck = liveCheck;
    }

    /**
     * Another codec for the track whose segment failed: a live stream's segment that isn't there (404, e.g. DrLupo's streams, whose
     * last audio segment did so), or one its server failed (500, 503). Not a regular video's 500 or 503: every format of it fails
     * the same (97MSK8B6 #1, #2: AVC answered 503 too, 7 s after VP9 did; 0CW972QK: every format, on three clients), and the swap
     * marked formats that play as failed. Nor a load of another track (e.g. subtitles), which no codec of the media fixes.
     *
     * @param track of the load that failed (see #trackOf): its own, not its URL's, which names the mime type only in the path of a
     *              YouTube manifest's segments. The app's own manifests have it in the query (mime=audio%2Fwebm, see
     *              YouTubeMPDBuilder), so an audio segment's 404 or 503 swapped the video (Q19MW058 #2: 1080p to 720p, 97MSK8B6 #5:
     *              VP9 to AVC).
     */
    public boolean fixError(Exception e, String track) {
        if (!(e instanceof InvalidResponseCodeException)) {
            return false;
        }

        boolean isAudio = PlayerFailure.TRACK_AUDIO.equals(track);

        if (!isAudio && !PlayerFailure.TRACK_VIDEO.equals(track)) {
            return false;
        }

        int responseCode = ((InvalidResponseCodeException) e).responseCode;
        boolean isLive = mLiveCheck != null && mLiveCheck.isLive();

        if (responseCode != 404 && !(isLive && (responseCode == 503 || responseCode == 500))) {
            return false;
        }

        if (System.currentTimeMillis() - mSelectionTimeMs < BLACKLIST_CHECK_MS) {
            return false;
        }

        return selectDifferentCodec(isAudio);
    }

    //public boolean fixError(ExoPlaybackException error) {
    //    if (error != null && error.getCause() instanceof DecoderInitializationException) {
    //        return selectDifferentCodec(MimeTypes.isAudio(((DecoderInitializationException) error.getCause()).mimeType));
    //    }
    //
    //    return false;
    //}

    private boolean selectDifferentCodec(boolean isAudio) {
        if (System.currentTimeMillis() - mSelectionTimeMs < BLACKLIST_CLEAR_MS) {
            return false;
        }

        Set<MediaTrack> tracks = isAudio ? mTrackSelectorManager.getAudioTracks() : mTrackSelectorManager.getVideoTracks();

        if (tracks == null) {
            return false;
        }

        MediaTrack currentTrack = getSelectedTrack(tracks);

        if (currentTrack == null || currentTrack.format == null || currentTrack.format.codecs == null) {
            return false;
        }

        MediaTrack nextTrack = null;

        for (MediaTrack track : tracks) {
            if (track.format == null) {
                continue;
            }

            // Voice boost is opt-in. The new track becomes the origin for later matches.
            if (isOtherCodec(currentTrack.format, track.format, isAudio)
                    && !TrackSelectorUtil.isUnwantedVb(currentTrack.format, track.format)) {
                nextTrack = track;
                break;
            }
        }

        if (nextTrack != null) {
            mTrackSelectorManager.selectTrack(nextTrack);
            mSelectionTimeMs = System.currentTimeMillis();
            return true;
        }

        return false;
    }

    //private boolean selectNextTrack() {
    //    if (mLastEx == null) {
    //        return false;
    //    }
    //
    //    if (System.currentTimeMillis() - mSelectionTimeMs < BLACKLIST_CLEAR_MS) {
    //        return false;
    //    }
    //
    //    Set<MediaTrack> tracks = isAudio(mLastEx) ? mTrackSelectorManager.getAudioTracks() : mTrackSelectorManager.getVideoTracks();
    //
    //    if (tracks == null) {
    //        return false;
    //    }
    //
    //    MediaTrack nextTrack = null;
    //    boolean afterSelected = false;
    //
    //    for (MediaTrack track : tracks) {
    //        if (track.isSelected) {
    //            afterSelected = true;
    //            continue;
    //        }
    //
    //        if (afterSelected) {
    //            nextTrack = track;
    //            break;
    //        }
    //    }
    //
    //    if (nextTrack != null) {
    //        mTrackSelectorManager.selectTrack(nextTrack);
    //        return true;
    //    }
    //
    //    mSelectionTimeMs = System.currentTimeMillis();
    //    return false;
    //}
    //
    //private boolean selectNextTrackOld() {
    //    if (mLastEx == null) {
    //        return false;
    //    }
    //
    //    if (System.currentTimeMillis() - mSelectionTimeMs > BLACKLIST_CLEAR_MS) {
    //        mBlacklistedTracks.clear();
    //    }
    //
    //    Set<MediaTrack> tracks = isAudio(mLastEx) ? mTrackSelectorManager.getAudioTracks() : mTrackSelectorManager.getVideoTracks();
    //    MediaTrack tmpTrack = null;
    //
    //    if (tracks == null) {
    //        return false;
    //    }
    //
    //    for (MediaTrack track : tracks) {
    //        if (track.isSelected) {
    //            addToBlacklist(track);
    //        } else {
    //            tmpTrack = track;
    //            if (!isBlacklisted(track)) {
    //                addToBlacklist(track);
    //                break;
    //            }
    //        }
    //    }
    //
    //    if (tmpTrack != null) {
    //        mTrackSelectorManager.selectTrack(tmpTrack);
    //        mSelectionTimeMs = System.currentTimeMillis();
    //        //Utils.postDelayed(mHandler, mSelectFirstTrack, SELECT_FIRST_TRACK_MS);
    //        return true;
    //    }
    //
    //    return false;
    //}
    //
    //private void selectFirstTrack() {
    //    if (mLastEx == null) {
    //        return;
    //    }
    //
    //    mBlacklistedTracks.clear();
    //
    //    Set<MediaTrack> tracks = isAudio(mLastEx) ? mTrackSelectorManager.getAudioTracks() : mTrackSelectorManager.getVideoTracks();
    //    MediaTrack tmpTrack = null;
    //
    //    for (MediaTrack track : tracks) {
    //        addToBlacklist(track);
    //        tmpTrack = track;
    //        break;
    //    }
    //
    //    if (tmpTrack != null) {
    //        mTrackSelectorManager.selectTrack(tmpTrack);
    //        mSelectionTimeMs = System.currentTimeMillis();
    //    }
    //}

    @Nullable
    private static MediaTrack getSelectedTrack(@Nullable Set<MediaTrack> tracks) {
        if (tracks == null) {
            return null;
        }

        for (MediaTrack track : tracks) {
            if (track.isSelected) {
                return track;
            }
        }

        return null;
    }

    /**
     * Another codec than the one in use. Of a video, another family (AVC, VP9, AV1) of the same size: a codec string alone tells AVC's
     * profiles apart, so a 1080p AVC's failure picked a 720p one (Q19MW058 #2). Of an audio, another codec string (e.g. AAC's HE
     * profile for its LC one), as before: an audio has no size.
     */
    static boolean isOtherCodec(Format current, Format other, boolean isAudio) {
        if (current.codecs == null || other.codecs == null) {
            return false;
        }

        if (isAudio) {
            return !current.codecs.equals(other.codecs);
        }

        return !Helpers.equals(TrackSelectorUtil.codecNameShort(current.codecs), TrackSelectorUtil.codecNameShort(other.codecs))
                && Math.max(current.width, current.height) == Math.max(other.width, other.height);
    }

    private boolean isBlacklisted(MediaTrack track) {
        for (Entry<MediaTrack, Long> entry : mBlacklistedTracks.entrySet()) {
            if (track == entry.getKey() && System.currentTimeMillis() - entry.getValue() < BLACKLIST_CLEAR_MS) {
                return true;
            }
        }

        return false;
    }

    private void addToBlacklist(MediaTrack track) {
        mBlacklistedTracks.put(track, System.currentTimeMillis());
    }

    public void fixEmptyChunk(Chunk chunk) {
        // Fix when just started new type live stream ahead of the position
        if (chunk instanceof ContainerMediaChunk) {
            long nextLoadPosition = (Long) Helpers.getField(chunk, "nextLoadPosition");
            if (nextLoadPosition == 0) {
                Log.e(TAG, "Stream position behind the timeline. Waiting for new data...");
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void onLoadError(int windowIndex, @Nullable MediaPeriodId mediaPeriodId, LoadEventInfo loadEventInfo,
                            MediaLoadData mediaLoadData, IOException error, boolean wasCanceled) {
        if (!(error instanceof InvalidResponseCodeException)) {
            return;
        }

        InvalidResponseCodeException ex = (InvalidResponseCodeException) error;
        String track = trackOf(mediaLoadData, ex.dataSpec.uri.toString());
        MediaTrack failed = PlayerFailure.TRACK_AUDIO.equals(track) ? getSelectedTrack(mTrackSelectorManager.getAudioTracks()) :
                PlayerFailure.TRACK_VIDEO.equals(track) ? getSelectedTrack(mTrackSelectorManager.getVideoTracks()) : null;

        boolean switched = fixError(error, track);

        if (mOnHttpError == null) {
            return;
        }

        mOnHttpError.onHttpError(ex.responseCode, track, PlayerFailure.describeHost(ex.dataSpec.uri.getHost()),
                failed != null ? failed.format : null, switched);
    }

    /**
     * Of a load: audio, video, text (subtitles) or other (e.g. a manifest). By the track it loads for, else its format: the URL names
     * the mime type of a DASH segment only, so every SABR request was other, like a subtitle's. Muxed (the URL list's) is video. The
     * URL names it in its path (a YouTube manifest's segment) or its query (the app's own manifests', see YouTubeMPDBuilder).
     */
    static String trackOf(@Nullable MediaLoadData data, String url) {
        if (data != null) {
            switch (data.trackType) {
                case C.TRACK_TYPE_AUDIO:
                    return PlayerFailure.TRACK_AUDIO;
                case C.TRACK_TYPE_VIDEO:
                case C.TRACK_TYPE_DEFAULT:
                    return PlayerFailure.TRACK_VIDEO;
                case C.TRACK_TYPE_TEXT:
                    return PlayerFailure.TRACK_TEXT;
            }

            // E.g. a sideloaded subtitle's (see SingleSampleMediaPeriod): its type is unknown, its format isn't
            String mimeType = data.trackFormat != null ? data.trackFormat.sampleMimeType : null;

            if (MimeTypes.isText(mimeType)) {
                return PlayerFailure.TRACK_TEXT;
            } else if (MimeTypes.isAudio(mimeType)) {
                return PlayerFailure.TRACK_AUDIO;
            } else if (MimeTypes.isVideo(mimeType)) {
                return PlayerFailure.TRACK_VIDEO;
            }
        }

        return Helpers.containsAny(url, "mime/audio", "mime=audio") ? PlayerFailure.TRACK_AUDIO
                : Helpers.containsAny(url, "mime/video", "mime=video") ? PlayerFailure.TRACK_VIDEO : PlayerFailure.TRACK_OTHER;
    }
}
