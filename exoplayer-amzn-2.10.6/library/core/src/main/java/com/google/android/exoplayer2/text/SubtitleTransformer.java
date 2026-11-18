package com.google.android.exoplayer2.text;

/**
 * Changes what a subtitle decoder has decoded before it's shown (see SimpleSubtitleDecoder#setTransformer).
 */
public interface SubtitleTransformer {
  /**
   * Called on the decoder's thread, once per decoded sample.
   *
   * @return what's shown instead, or the subtitle itself
   */
  Subtitle transform(Subtitle subtitle);
}
