package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LiveEdgeTest {
    private static final long WINDOW_MS = 4 * 60 * 60 * 1_000;

    @Test
    public void playedFromTheOfficialBufferByDefaultAndFromTheEndWithoutIt() {
        assertEquals(15_000, LiveEdge.getBufferMs(false));
        assertEquals(0, LiveEdge.getBufferMs(true));
    }

    @Test
    public void theStartPositionIsAtTheLivePosition() {
        assertFalse(LiveEdge.isBehind(WINDOW_MS, WINDOW_MS - LiveEdge.getBufferMs(false), false));
        assertFalse(LiveEdge.isBehind(WINDOW_MS, WINDOW_MS - LiveEdge.getBufferMs(true), true));
    }

    @Test
    public void behindOnlyPastFiveSecondsMoreThanTheBuffer() {
        assertFalse(LiveEdge.isBehind(WINDOW_MS, WINDOW_MS - 20_000, false));
        assertTrue(LiveEdge.isBehind(WINDOW_MS, WINDOW_MS - 20_001, false));

        assertFalse(LiveEdge.isBehind(WINDOW_MS, WINDOW_MS - 5_000, true));
        assertTrue(LiveEdge.isBehind(WINDOW_MS, WINDOW_MS - 5_001, true));
    }

    @Test
    public void unknownDurationOrPositionIsntBehind() {
        // Not prepared yet: the controls show -1 for both
        assertFalse(LiveEdge.isBehind(-1, -1, false));
        assertFalse(LiveEdge.isBehind(-1, 0, false));
    }
}
