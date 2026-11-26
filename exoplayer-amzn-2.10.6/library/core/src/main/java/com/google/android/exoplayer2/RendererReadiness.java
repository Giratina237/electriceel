/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2;

import androidx.annotation.Nullable;

/**
 * MOD: tells which of its renderers the player waits for. The player is ready only once every enabled
 * renderer is (see ExoPlayerImplInternal#doSomeWork), and nothing said which one wasn't: a video
 * whose first frame was shown played 1.7 s later with 30 s buffered, and its record couldn't tell
 * whether the sound or the picture held it.
 */
public final class RendererReadiness {

  /**
   * Told on the playback thread, each time its loop looks at a renderer: about a hundred times a
   * second while the player buffers or plays. It must return at once.
   */
  public interface Listener {

    /**
     * @param trackType The renderer's, e.g. {@link C#TRACK_TYPE_AUDIO}.
     * @param isReady Whether it can play from where it is (or ended, or waits for its next stream).
     */
    void onReadiness(int trackType, boolean isReady);
  }

  @Nullable private static volatile Listener listener;

  private RendererReadiness() {}

  /** Sets the listener of every player's renderers, null for none. */
  public static void setListener(@Nullable Listener listener) {
    RendererReadiness.listener = listener;
  }

  /* package */ static void onReadiness(Renderer renderer, boolean isReady) {
    Listener listener = RendererReadiness.listener;
    if (listener != null) {
      listener.onReadiness(renderer.getTrackType(), isReady);
    }
  }
}
