package com.google.android.exoplayer2.text;

import java.util.List;

/**
 * A subtitle file's cues, every one of them in the file's order (see WebvttSubtitle): what the file holds, read without asking for
 * the cues of each of its times.
 */
public interface SubtitleCues {
  List<? extends Cue> getAllCues();
}
