package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * The client a refused video was moved to, remembered for when it's opened again (89HAAW4J, XWR8QQN7, F0SVV9N8: one video refused by
 * TV in four runs, relearned each time)
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class VideoClientPicksTest {
    private final AtomicLong mNow = new AtomicLong(1_000_000);

    @Before
    public void setUp() {
        VideoClientPicks.resetForTesting(getContext(), mNow::get);
    }

    @After
    public void tearDown() {
        VideoClientPicks.resetForTesting(getContext(), null);
    }

    @Test
    public void aVideoMovedIsAskedOfItsClient() {
        VideoClientPicks.remember(getContext(), "t4P-Axqv6GA", "TV_SAMSUNG");

        assertEquals("TV_SAMSUNG", VideoClientPicks.get(getContext(), "t4P-Axqv6GA"));
        assertNull(VideoClientPicks.get(getContext(), "another"));
    }

    /**
     * The client failed the video (H8ZP1GPV: TV_SAMSUNG answered 503 to every segment): that video's only
     */
    @Test
    public void aClientThatFailedItIsForgotten() {
        VideoClientPicks.remember(getContext(), "Hf-CqpBeDs0", "TV_SAMSUNG");
        VideoClientPicks.remember(getContext(), "another", "VISIONOS");

        VideoClientPicks.forget(getContext(), "Hf-CqpBeDs0");

        assertNull(VideoClientPicks.get(getContext(), "Hf-CqpBeDs0"));
        assertEquals("VISIONOS", VideoClientPicks.get(getContext(), "another"));
    }

    @Test
    public void aWeekOnItsForgotten() {
        VideoClientPicks.remember(getContext(), "t4P-Axqv6GA", "TV_SAMSUNG");

        mNow.addAndGet(VideoClientPicks.KEEP_MS + 1);

        assertNull(VideoClientPicks.get(getContext(), "t4P-Axqv6GA"));
    }

    @Test
    public void theOldestIsLeftOut() {
        for (int i = 0; i < VideoClientPicks.MAX_VIDEOS; i++) {
            VideoClientPicks.remember(getContext(), "video" + i, "TV_SAMSUNG");
            mNow.incrementAndGet();
        }

        VideoClientPicks.remember(getContext(), "newest", "VISIONOS");

        assertNull(VideoClientPicks.get(getContext(), "video0"));
        assertEquals("TV_SAMSUNG", VideoClientPicks.get(getContext(), "video1"));
        assertEquals("VISIONOS", VideoClientPicks.get(getContext(), "newest"));
    }

    private static Context getContext() {
        return RuntimeEnvironment.getApplication();
    }
}
