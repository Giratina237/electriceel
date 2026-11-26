package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.service.VideoStateService;
import com.liskovsoft.smartyoutubetv2.common.app.views.PlaybackView;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;

/**
 * A live stream that has caught up with the end of its window goes back to the live position on buffering, but not before its start
 * has played, where restorePosition has just placed it, nor when the live position is the end itself (the buffer on streams off): each
 * such seek cancelled the loads in flight (2M1DY5CD -BwLZBMKW4g: three seeks to the end in its first second, 4961 ms; MD8RQZM2: 6
 * live starts with 2-3 each)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveEndSeekTest {
    /**
     * 9:25 of the stream since it began: the end of its window, as a SABR live stream's is by the clock (PlaybackFragment#getDurationMs)
     */
    private static final long DURATION_MS = 565_000;
    private static final String LIVE_ID = "-BwLZBMKW4g";

    @Before
    public void setUp() {
        getStateService().clear();
    }

    @After
    public void tearDown() {
        getStateService().clear();
        getTweaksData().setBufferOnStreamsDisabled(false);
        Utils.sHandler.removeCallbacksAndMessages(null);
    }

    /**
     * 2M1DY5CD -BwLZBMKW4g: placed at the end, then sent to the end again 26 ms later (the clock's end 26 ms on) and 1020 ms later
     * (994 ms after that, still within a second of it: the position stands still while it buffers)
     */
    @Test
    public void aStartAtTheEndWithoutTheBufferIsntSentThereAgainWhileItBuffers() {
        getTweaksData().setBufferOnStreamsDisabled(true);
        TestStateController controller = new TestStateController();

        controller.open(live());
        assertEquals(DURATION_MS, controller.player.positionMs);
        assertEquals(1, controller.player.seeks);

        controller.player.durationMs += 26;
        controller.onBuffering();
        controller.player.durationMs += 994;
        controller.onBuffering();

        assertEquals(1, controller.player.seeks);
        assertEquals(DURATION_MS, controller.player.positionMs);
    }

    /**
     * Played and caught up with the end: without the buffer there's no live position behind it to go back to, and the end as it is
     * by now is ahead
     */
    @Test
    public void aPlayedStreamAtTheEndWithoutTheBufferIsntSentToTheEnd() {
        getTweaksData().setBufferOnStreamsDisabled(true);
        TestStateController controller = new TestStateController();
        controller.open(live());
        controller.onPlay();
        int seeks = controller.player.seeks;

        controller.player.durationMs += 500;
        controller.onBuffering();

        assertEquals(seeks, controller.player.seeks);
        assertEquals(DURATION_MS, controller.player.positionMs);
    }

    /**
     * The live position before the start plays is where restorePosition (here, a time code) placed it, even at the end
     */
    @Test
    public void aStartPlacedAtTheEndIsntMovedBeforeItPlays() {
        TestStateController controller = new TestStateController();
        Video live = live();
        live.pendingPosMs = DURATION_MS - 500;

        controller.open(live);
        int seeks = controller.player.seeks;
        controller.onBuffering();

        assertEquals(seeks, controller.player.seeks);
        assertEquals(DURATION_MS - 500, controller.player.positionMs);
    }

    /**
     * As before: played and caught up with the end, it goes back to the live position, the buffer behind the end
     */
    @Test
    public void aPlayedStreamAtTheEndGoesBackToTheLivePosition() {
        TestStateController controller = new TestStateController();
        controller.open(live());
        controller.onPlay();

        controller.player.positionMs = DURATION_MS - 500;
        controller.onBuffering();

        assertEquals(DURATION_MS - LiveEdge.getBufferMs(false), controller.player.positionMs);
    }

    private static Video live() {
        Video video = new Video();
        video.videoId = LIVE_ID;
        video.isLive = true;
        return video;
    }

    private static VideoStateService getStateService() {
        return VideoStateService.instance(RuntimeEnvironment.getApplication());
    }

    private static PlayerTweaksData getTweaksData() {
        return PlayerTweaksData.instance(RuntimeEnvironment.getApplication());
    }

    private static class TestStateController extends VideoStateController {
        private final RecordingPlayer player = new RecordingPlayer();
        private Video mVideo;

        private TestStateController() {
            setAltContext(RuntimeEnvironment.getApplication());
        }

        @Override
        public PlaybackView getPlayer() {
            return player.view;
        }

        @Override
        public Video getVideo() {
            return mVideo;
        }

        /**
         * The player's calls when a video is opened: the new video, its source, then the loaded stream
         */
        private void open(Video video) {
            onNewVideo(video);
            mVideo = video;
            player.positionMs = 0;
            player.seeks = 0;
            onSourceChanged(video);
            onVideoLoaded(video);
        }
    }

    /**
     * A live stream whose window ends at durationMs, the position at the last one set, the seeks counted. Returns the defaults
     * (false, 0, null) for every other call.
     */
    private static class RecordingPlayer {
        private long positionMs;
        private long durationMs = DURATION_MS;
        private int seeks;
        private final PlaybackView view = (PlaybackView) Proxy.newProxyInstance(PlaybackView.class.getClassLoader(),
                new Class<?>[]{PlaybackView.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "setPositionMs":
                        case "setResumePositionMs": // a resume (see ExoPlayerController#setResumePositionMs)
                            positionMs = (Long) args[0];
                            seeks++;
                            return null;
                        case "getPositionMs":
                            return positionMs;
                        case "getDurationMs":
                            return durationMs;
                    }

                    Class<?> type = method.getReturnType();

                    if (type == boolean.class) {
                        return false;
                    } else if (type == int.class) {
                        return 0;
                    } else if (type == long.class) {
                        return 0L;
                    } else if (type == float.class) {
                        return 0f;
                    } else if (type == double.class) {
                        return 0d;
                    }

                    return null;
                });
    }
}
