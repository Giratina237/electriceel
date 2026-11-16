package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RetryBackoffTest {
    @Test
    public void eachWaitIsTwiceTheOneBeforeUpToTheLongest() {
        RetryBackoff backoff = new RetryBackoff(1_000, 30_000);

        assertEquals(1_000, backoff.next("a"));
        assertEquals(2_000, backoff.next("a"));
        assertEquals(4_000, backoff.next("a"));
        assertEquals(8_000, backoff.next("a"));
        assertEquals(16_000, backoff.next("a"));
        assertEquals(30_000, backoff.next("a"));
        assertEquals(30_000, backoff.next("a"));
        assertEquals(7, backoff.getCount("a"));
    }

    @Test
    public void anotherKeyStartsOver() {
        RetryBackoff backoff = new RetryBackoff(1_000, 30_000);
        backoff.next("a");
        backoff.next("a");

        assertEquals(0, backoff.getCount("b"));
        assertEquals(1_000, backoff.next("b"));
        assertEquals(0, backoff.getCount("a"));
    }

    @Test
    public void aResetStartsOver() {
        RetryBackoff backoff = new RetryBackoff(2_000, 30_000);
        backoff.next("a");
        backoff.next("a");
        backoff.reset();

        assertEquals(2_000, backoff.next("a"));
    }

    /**
     * Tried for hours: the shift doesn't overflow
     */
    @Test
    public void manyTriesStayAtTheLongest() {
        RetryBackoff backoff = new RetryBackoff(1_000, 30_000);

        for (int i = 0; i < 200; i++) {
            backoff.next("a");
        }

        assertEquals(30_000, backoff.next("a"));
    }
}
