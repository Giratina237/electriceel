package com.google.android.exoplayer2.text;

/**
 * Over a word of a cue's text: when the word starts to be said, as the subtitles time it. YouTube's automatic captions time each
 * word of a line but the first, which starts with the cue (see WebvttCueParser). Not a style: nothing draws it.
 */
public final class WordTimeSpan {
  /** In the subtitle's own time, as its cues' times are. */
  public final long timeUs;

  public WordTimeSpan(long timeUs) {
    this.timeUs = timeUs;
  }
}
