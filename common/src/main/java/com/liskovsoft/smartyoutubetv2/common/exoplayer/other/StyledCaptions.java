package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.Subtitle;
import com.google.android.exoplayer2.text.SubtitleCues;
import com.google.android.exoplayer2.text.webvtt.WebvttCue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Captions in the colors, italics and places their author gave them (see CaptionStylesData, issue #95). YouTube's WebVTT has them:
 * a color per speaker (e.g. Tom Scott's videos), italics for a narrator, lines at the top of the screen while something's shown at
 * its bottom.<br/>
 * Of a cue's place only its line at the top is kept. YouTube's WebVTT also puts most cues off the middle, by what it says of their
 * position and size, while its own player shows them in the middle: all at the bottom stay where the subtitle position setting
 * puts them.
 */
public final class StyledCaptions {
    private static final Pattern LINE_BREAK = Pattern.compile("\\s*\n\\s*");
    // The decoded file of the text track shown, or the last one: written on the decoder's thread (see SentenceSubtitles)
    private static volatile boolean sIsTrackStyled;

    private StyledCaptions() {
    }

    /**
     * A new text track is about to be decoded
     */
    static void onTrackChanged() {
        sIsTrackStyled = false;
    }

    /**
     * A whole subtitle file was decoded (see SentenceSubtitles): it's decoded again after each seek
     */
    static void onFileDecoded(@NonNull Subtitle subtitle) {
        sIsTrackStyled = isStyled(subtitle);
    }

    /**
     * Whether the text track decoded last has them. Read with the subtitles shown or not.
     */
    public static boolean isTrackStyled() {
        return sIsTrackStyled;
    }

    static boolean isStyled(@NonNull Subtitle subtitle) {
        if (!(subtitle instanceof SubtitleCues)) {
            return false;
        }

        for (Cue cue : ((SubtitleCues) subtitle).getAllCues()) {
            if (isStyled(cue)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Only WebVTT's: YouTube's TTML gives every cue its own default, white on black. One without text (e.g. an mp4 cue box without
     * its payload) has nothing to show.
     */
    static boolean isStyled(@NonNull Cue cue) {
        return cue instanceof WebvttCue && cue.text != null && (isAtTop(cue) || hasStyle(cue.text));
    }

    static boolean containsStyled(@NonNull List<Cue> cues) {
        for (Cue cue : cues) {
            if (isStyled(cue)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The cues as their author placed them: those at the top together at the top, the others together at the bottom, each in the
     * middle. The cues of a time show at once, so they're put together not to be drawn over each other.
     */
    static List<Cue> place(@NonNull List<Cue> cues) {
        SpannableStringBuilder top = null;
        Cue topCue = null;
        SpannableStringBuilder bottom = null;

        for (Cue cue : cues) {
            if (cue.text == null || cue.text.length() == 0) {
                continue;
            }

            if (isAtTop(cue)) {
                if (topCue == null || cue.line < topCue.line) {
                    topCue = cue;
                }
                top = append(top, cue.text);
            } else {
                bottom = append(bottom, cue.text);
            }
        }

        List<Cue> result = new ArrayList<>(2);

        if (topCue != null) {
            result.add(new Cue(top, null, topCue.line, topCue.lineType, topCue.lineAnchor, Cue.DIMEN_UNSET, Cue.TYPE_UNSET,
                    Cue.DIMEN_UNSET));
        }

        if (bottom != null) {
            result.add(new Cue(bottom));
        }

        return result;
    }

    /**
     * The cues as they were shown before they had their styles, when they aren't: a cue with styles keeps its lines now (see
     * WebvttCueParser), which were joined, and SubtitleManager#forceCenterAlignment takes a line break for one of automatic captions
     */
    static List<Cue> withJoinedLines(@NonNull List<Cue> cues) {
        List<Cue> result = null;

        for (int i = 0; i < cues.size(); i++) {
            Cue cue = cues.get(i);

            if (isStyled(cue) && TextUtils.indexOf(cue.text, '\n') >= 0) {
                if (result == null) {
                    result = new ArrayList<>(cues);
                }

                result.set(i, new Cue(LINE_BREAK.matcher(cue.text).replaceAll(" ")));
            }
        }

        return result != null ? result : cues;
    }

    private static SpannableStringBuilder append(@Nullable SpannableStringBuilder text, @NonNull CharSequence cueText) {
        if (text == null) {
            return new SpannableStringBuilder(cueText);
        }

        return text.append('\n').append(cueText);
    }

    /**
     * Above the middle of the screen: by a fraction of its height, or by lines from its top
     */
    private static boolean isAtTop(@NonNull Cue cue) {
        if (cue.line == Cue.DIMEN_UNSET) {
            return false;
        }

        return cue.lineType == Cue.LINE_TYPE_NUMBER ? cue.line >= 0 : cue.line < 0.5f;
    }

    /**
     * A color, italics, bold, an underline or a strikethrough. Not the times of words (see WordTimeSpan), sizes or fonts.
     */
    private static boolean hasStyle(@Nullable CharSequence text) {
        if (!(text instanceof Spanned)) {
            return false;
        }

        Spanned spanned = (Spanned) text;
        int length = spanned.length();

        return spanned.getSpans(0, length, ForegroundColorSpan.class).length > 0
                || spanned.getSpans(0, length, BackgroundColorSpan.class).length > 0
                || spanned.getSpans(0, length, StyleSpan.class).length > 0
                || spanned.getSpans(0, length, UnderlineSpan.class).length > 0
                || spanned.getSpans(0, length, StrikethroughSpan.class).length > 0;
    }
}
