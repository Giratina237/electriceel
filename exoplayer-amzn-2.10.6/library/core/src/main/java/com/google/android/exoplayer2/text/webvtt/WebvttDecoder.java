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
package com.google.android.exoplayer2.text.webvtt;

import android.text.TextUtils;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.text.SimpleSubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderException;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.Util;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A {@link SimpleSubtitleDecoder} for WebVTT.
 * <p>
 * @see <a href="http://dev.w3.org/html5/webvtt">WebVTT specification</a>
 */
public final class WebvttDecoder extends SimpleSubtitleDecoder {

  private static final int EVENT_NONE = -1;
  private static final int EVENT_END_OF_FILE = 0;
  private static final int EVENT_COMMENT = 1;
  private static final int EVENT_STYLE_BLOCK = 2;
  private static final int EVENT_CUE = 3;

  private static final String COMMENT_START = "NOTE";
  private static final String STYLE_START = "STYLE";
  // MOD: YouTube writes its cues' styles (e.g. a color per speaker) into the header, between these lines, not into a STYLE block
  private static final String YOUTUBE_STYLE_START = "Style:";
  private static final String YOUTUBE_STYLE_END = "##";
  // YouTube's "green" is #00FF00 (CSS's lime), as its own player and its srv3 captions have it. CSS's green is #008000.
  private static final Pattern YOUTUBE_GREEN = Pattern.compile("(color\\s*:\\s*)green\\b");

  private final WebvttCueParser cueParser;
  private final ParsableByteArray parsableWebvttData;
  private final WebvttCue.Builder webvttCueBuilder;
  private final CssParser cssParser;
  private final List<WebvttCssStyle> definedStyles;

  public WebvttDecoder() {
    super("WebvttDecoder");
    cueParser = new WebvttCueParser();
    parsableWebvttData = new ParsableByteArray();
    webvttCueBuilder = new WebvttCue.Builder();
    cssParser = new CssParser();
    definedStyles = new ArrayList<>();
  }

  @Override
  protected WebvttSubtitle decode(byte[] bytes, int length, boolean reset)
      throws SubtitleDecoderException {
    parsableWebvttData.reset(bytes, length);
    // Initialization for consistent starting state.
    webvttCueBuilder.reset();
    definedStyles.clear();

    // Validate the first line of the header, and skip the remainder.
    try {
      WebvttParserUtil.validateWebvttHeaderLine(parsableWebvttData);
    } catch (ParserException e) {
      throw new SubtitleDecoderException(e);
    }
    readHeader(parsableWebvttData);

    int event;
    ArrayList<WebvttCue> subtitles = new ArrayList<>();
    while ((event = getNextEvent(parsableWebvttData)) != EVENT_END_OF_FILE) {
      if (event == EVENT_COMMENT) {
        skipComment(parsableWebvttData);
      } else if (event == EVENT_STYLE_BLOCK) {
        if (!subtitles.isEmpty()) {
          throw new SubtitleDecoderException("A style block was found after the first cue.");
        }
        parsableWebvttData.readLine(); // Consume the "STYLE" header.
        definedStyles.addAll(cssParser.parseBlock(parsableWebvttData));
      } else if (event == EVENT_CUE) {
        if (cueParser.parseCue(parsableWebvttData, webvttCueBuilder, definedStyles)) {
          subtitles.add(webvttCueBuilder.build());
          webvttCueBuilder.reset();
        }
      }
    }
    return new WebvttSubtitle(subtitles);
  }

  /**
   * Positions the input right before the next event, and returns the kind of event found. Does not
   * consume any data from such event, if any.
   *
   * @return The kind of event found.
   */
  private static int getNextEvent(ParsableByteArray parsableWebvttData) {
    int foundEvent = EVENT_NONE;
    int currentInputPosition = 0;
    while (foundEvent == EVENT_NONE) {
      currentInputPosition = parsableWebvttData.getPosition();
      String line = parsableWebvttData.readLine();
      if (line == null) {
        foundEvent = EVENT_END_OF_FILE;
      } else if (STYLE_START.equals(line)) {
        foundEvent = EVENT_STYLE_BLOCK;
      } else if (line.startsWith(COMMENT_START)) {
        foundEvent = EVENT_COMMENT;
      } else {
        foundEvent = EVENT_CUE;
      }
    }
    parsableWebvttData.setPosition(currentInputPosition);
    return foundEvent;
  }

  /**
   * MOD: skips the rest of the header, but for the styles YouTube writes into it
   */
  private void readHeader(ParsableByteArray parsableWebvttData) {
    String line;
    while (!TextUtils.isEmpty(line = parsableWebvttData.readLine())) {
      if (!YOUTUBE_STYLE_START.equals(line)) {
        continue;
      }

      StringBuilder css = new StringBuilder();
      while (!TextUtils.isEmpty(line = parsableWebvttData.readLine()) && !YOUTUBE_STYLE_END.equals(line)) {
        css.append(line).append('\n');
      }

      String fixedCss = YOUTUBE_GREEN.matcher(css).replaceAll("$1lime");
      definedStyles.addAll(cssParser.parseBlock(new ParsableByteArray(Util.getUtf8Bytes(fixedCss))));

      if (TextUtils.isEmpty(line)) {
        // The header ended within the styles
        return;
      }
    }
  }

  private static void skipComment(ParsableByteArray parsableWebvttData) {
    while (!TextUtils.isEmpty(parsableWebvttData.readLine())) {}
  }

}
