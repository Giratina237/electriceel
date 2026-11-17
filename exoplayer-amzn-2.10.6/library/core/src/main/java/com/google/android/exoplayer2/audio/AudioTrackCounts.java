package com.google.android.exoplayer2.audio;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * SmartTube fix: the {@link android.media.AudioTrack}s the players of the app's process built and released, for a refused one to be
 * told apart: tracks the app still holds (its share of AudioFlinger's memory, 10 MB per app on an Nvidia Shield, is full of them), or
 * a device that refuses whatever the app holds. A Shield refused every track for 20 minutes and more, a new process's first one too,
 * and played again only once the device was restarted (FARAJV5R #40–#41, 9M6NBWN7): refused with none open, it's the device's (see
 * ErrorFixerController#applyDeviceAudioRefusal).
 */
public final class AudioTrackCounts {

  private static final AtomicInteger built = new AtomicInteger();
  private static final AtomicInteger refused = new AtomicInteger();
  private static final AtomicInteger releasing = new AtomicInteger();
  private static final AtomicInteger released = new AtomicInteger();

  private AudioTrackCounts() {}

  /** One was built and initialized. */
  /* package */ static void onBuilt() {
    built.incrementAndGet();
  }

  /** The device refused one (see AudioSink.InitializationException): it was released at once. */
  /* package */ static void onRefused() {
    refused.incrementAndGet();
  }

  /** One is let go: its release runs on a thread of its own, as it can take a while. */
  /* package */ static void onReleaseStarted() {
    releasing.incrementAndGet();
  }

  /** One that was let go is released. */
  /* package */ static void onReleased() {
    releasing.decrementAndGet();
    released.incrementAndGet();
  }

  /** Built since the process started. */
  public static int getBuilt() {
    return built.get();
  }

  /** Refused since the process started. */
  public static int getRefused() {
    return refused.get();
  }

  /** Built and not released yet, the ones being released included. */
  public static int getOpen() {
    return built.get() - released.get();
  }

  /** Let go and not released yet. */
  public static int getReleasing() {
    return releasing.get();
  }
}
