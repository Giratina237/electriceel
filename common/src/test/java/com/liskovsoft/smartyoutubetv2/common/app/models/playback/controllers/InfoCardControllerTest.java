package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.mediaserviceinterfaces.data.InfoCardItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * When an info card's teaser shows, which cards can be shown, and what a click opens
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InfoCardControllerTest {
    // As YouTube gives them: 2:34.878 in, a teaser of 6 s, no end
    private static final long START_MS = 154_878;
    private static final long TEASER_MS = 6_000;

    private static final long SHOW_MS = InfoCardController.SHOW_MS;

    @Test
    public void teaserShowsWhenPlaybackReachesTheCard() {
        List<InfoCardItem> items = Collections.singletonList(TestItem.video("v1", START_MS));
        InfoCardController.TeaserTimer timer = new InfoCardController.TeaserTimer();

        assertNull(timer.update(items, START_MS - 1, 0, true));
        assertSame(items.get(0), timer.update(items, START_MS, 500, true));
    }

    /**
     * Five seconds of real time at 1x, as long at 2x (where YouTube's 6 s of video would last 3 s)
     */
    @Test
    public void staysFiveSecondsAtAnySpeed() {
        List<InfoCardItem> items = Collections.singletonList(TestItem.video("v1", START_MS));
        InfoCardController.TeaserTimer normal = new InfoCardController.TeaserTimer();
        InfoCardController.TeaserTimer twice = new InfoCardController.TeaserTimer();

        assertSame(items.get(0), normal.update(items, START_MS, 0, true));
        assertSame(items.get(0), normal.update(items, START_MS + SHOW_MS - 1, SHOW_MS - 1, true));
        assertNull(normal.update(items, START_MS + SHOW_MS, SHOW_MS, true));

        assertSame(items.get(0), twice.update(items, START_MS, 0, true));
        assertSame(items.get(0), twice.update(items, START_MS + 2 * (SHOW_MS - 1), SHOW_MS - 1, true));
        assertNull(twice.update(items, START_MS + 2 * SHOW_MS, SHOW_MS, true));
    }

    /**
     * The card comes while the controls are up (on the Shield they stay about 7 s after a seek): its five seconds start once they're
     * gone, rather than running out behind them
     */
    @Test
    public void timeUnderTheControlsDoesntCount() {
        List<InfoCardItem> items = Collections.singletonList(TestItem.video("v1", START_MS));
        InfoCardController.TeaserTimer timer = new InfoCardController.TeaserTimer();

        assertSame(items.get(0), timer.update(items, START_MS, 0, false));
        assertSame(items.get(0), timer.update(items, START_MS + 7_000, 7_000, false));
        assertSame(items.get(0), timer.update(items, START_MS + 7_500, 7_500, true));
        assertSame(items.get(0), timer.update(items, START_MS + 7_000 + SHOW_MS - 1, 7_000 + SHOW_MS - 1, true));
        assertNull(timer.update(items, START_MS + 7_000 + SHOW_MS, 7_000 + SHOW_MS, true));
    }

    /**
     * Paused on the card: its time is up all the same, and it doesn't come back while the video stays there
     */
    @Test
    public void notBackWhilePausedOnIt() {
        List<InfoCardItem> items = Collections.singletonList(TestItem.video("v1", START_MS));
        InfoCardController.TeaserTimer timer = new InfoCardController.TeaserTimer();

        assertSame(items.get(0), timer.update(items, START_MS + 1_000, 0, true));
        assertNull(timer.update(items, START_MS + 1_000, SHOW_MS, true));
        assertNull(timer.update(items, START_MS + 1_000, 60_000, true));
    }

    /**
     * Seeking back before the card: its teaser goes at once, and shows again when played into
     */
    @Test
    public void seekingBackHidesItTillPlayedIntoAgain() {
        List<InfoCardItem> items = Collections.singletonList(TestItem.video("v1", START_MS));
        InfoCardController.TeaserTimer timer = new InfoCardController.TeaserTimer();

        assertSame(items.get(0), timer.update(items, START_MS, 0, true));
        assertNull(timer.update(items, 60_000, 1_000, true));
        assertSame(items.get(0), timer.update(items, START_MS, 2_000, true));
        assertSame(items.get(0), timer.update(items, START_MS + 1_000, 2_000 + SHOW_MS - 1, true));
    }

    /**
     * A card that ends before the teaser's time is up: the teaser goes then
     */
    @Test
    public void teaserGoesAtTheCardsEnd() {
        TestItem item = TestItem.video("v1", START_MS);
        item.endMs = START_MS + 2_000;
        List<InfoCardItem> items = Collections.singletonList(item);
        InfoCardController.TeaserTimer timer = new InfoCardController.TeaserTimer();

        assertSame(item, timer.update(items, START_MS, 0, true));
        assertSame(item, timer.update(items, START_MS + 1_999, 1_999, true));
        assertNull(timer.update(items, START_MS + 2_000, 2_000, true));
    }

    /**
     * The next card's teaser takes over from the one on the screen, for its own five seconds (YouTube gives them in no order)
     */
    @Test
    public void nextCardTakesOver() {
        TestItem later = TestItem.video("v2", START_MS + 3_000);
        TestItem earlier = TestItem.video("v1", START_MS);
        List<InfoCardItem> items = Arrays.asList(later, earlier);
        InfoCardController.TeaserTimer timer = new InfoCardController.TeaserTimer();

        assertSame(earlier, timer.update(items, START_MS, 0, true));
        assertSame(earlier, timer.update(items, START_MS + 2_999, 2_999, true));
        assertSame(later, timer.update(items, START_MS + 3_000, 3_000, true));
        assertSame(later, timer.update(items, START_MS + 3_000 + SHOW_MS - 1, 3_000 + SHOW_MS - 1, true));
        assertNull(timer.update(items, START_MS + 3_000 + SHOW_MS, 3_000 + SHOW_MS, true));
    }

    /**
     * A seek to where two teasers are due: the one that started last, which the other doesn't then replace
     */
    @Test
    public void ofTwoAtOnceTheLaterOne() {
        TestItem later = TestItem.video("v2", START_MS + 3_000);
        TestItem earlier = TestItem.video("v1", START_MS);
        List<InfoCardItem> items = Arrays.asList(earlier, later);
        InfoCardController.TeaserTimer timer = new InfoCardController.TeaserTimer();

        assertSame(later, timer.update(items, START_MS + 3_500, 0, true));
        assertSame(later, timer.update(items, START_MS + 4_000, 500, true));
        assertNull(timer.update(items, START_MS + 3_500 + SHOW_MS, SHOW_MS, true));
    }

    @Test
    public void cardsWithoutTargetOrTeaserAreDropped() {
        TestItem video = TestItem.video("v1", START_MS);
        TestItem noVideoId = TestItem.video(null, START_MS);
        TestItem unknownKind = TestItem.video("v2", START_MS);
        unknownKind.type = -1;
        TestItem noTime = TestItem.video("v3", -1);
        // In YouTube's list without a teaser: it never shows over the video
        TestItem noTeaser = TestItem.video("v4", 0);
        noTeaser.teaserDurationMs = 0;
        TestItem channel = TestItem.channel("UC1");
        TestItem channelWithoutId = TestItem.channel(null);
        TestItem link = TestItem.link("https://example.com/");
        TestItem linkWithoutUrl = TestItem.link(null);
        TestItem playlistPage = TestItem.playlist(null, "PL1");

        List<InfoCardItem> result = InfoCardController.filterCards(Arrays.asList(video, noVideoId, unknownKind, noTime, noTeaser,
                channel, channelWithoutId, link, linkWithoutUrl, playlistPage, null));

        assertEquals(Arrays.asList(video, channel, link, playlistPage), result);
        assertTrue(InfoCardController.filterCards(null).isEmpty());
    }

    @Test
    public void videoCardOpensAsASuggestion() {
        Video video = InfoCardController.toVideo(TestItem.video("v1", START_MS));

        assertEquals("v1", video.videoId);
        assertNull(video.playlistId);
        assertNull(video.channelId);
        assertEquals("Title", video.title);
        assertEquals("https://i.ytimg.com/vi/v1/mqdefault.jpg", video.cardImageUrl);
    }

    /**
     * Its video plays in the playlist
     */
    @Test
    public void playlistCardPlaysInThePlaylist() {
        Video video = InfoCardController.toVideo(TestItem.playlist("v1", "PL1"));

        assertEquals("v1", video.videoId);
        assertEquals("PL1", video.playlistId);
    }

    @Test
    public void channelCardOpensTheChannel() {
        Video video = InfoCardController.toVideo(TestItem.channel("UC1"));

        assertEquals("UC1", video.channelId);
        assertNull(video.videoId);
        assertTrue(video.hasChannel());
    }

    /**
     * A link isn't a suggestion: it's shown as a QR code
     */
    @Test
    public void linkCardIsNoSuggestion() {
        assertNull(InfoCardController.toVideo(TestItem.link("https://example.com/")));
    }

    private static final class TestItem implements InfoCardItem {
        int type;
        String videoId;
        String playlistId;
        String channelId;
        String url;
        long startMs;
        long endMs = -1;
        long teaserDurationMs = TEASER_MS;

        static TestItem video(String videoId, long startMs) {
            TestItem item = new TestItem();
            item.type = TYPE_VIDEO;
            item.videoId = videoId;
            item.startMs = startMs;
            return item;
        }

        static TestItem playlist(String videoId, String playlistId) {
            TestItem item = video(videoId, START_MS);
            item.type = TYPE_PLAYLIST;
            item.playlistId = playlistId;
            return item;
        }

        static TestItem channel(String channelId) {
            TestItem item = video(null, START_MS);
            item.type = TYPE_CHANNEL;
            item.channelId = channelId;
            return item;
        }

        static TestItem link(String url) {
            TestItem item = video(null, START_MS);
            item.type = TYPE_LINK;
            item.url = url;
            return item;
        }

        @Override
        public int getType() {
            return type;
        }

        @Override
        public String getTeaser() {
            return "Teaser";
        }

        @Override
        public String getTitle() {
            return "Title";
        }

        @Override
        public String getMetadata() {
            return null;
        }

        @Override
        public String getBadge() {
            return null;
        }

        @Override
        public String getImageUrl() {
            return videoId != null ? "https://i.ytimg.com/vi/" + videoId + "/mqdefault.jpg" : null;
        }

        @Override
        public String getVideoId() {
            return videoId;
        }

        @Override
        public String getPlaylistId() {
            return playlistId;
        }

        @Override
        public String getChannelId() {
            return channelId;
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public long getStartMs() {
            return startMs;
        }

        @Override
        public long getEndMs() {
            return endMs;
        }

        @Override
        public long getTeaserDurationMs() {
            return teaserDurationMs;
        }
    }
}
