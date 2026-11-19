package com.google.android.exoplayer2.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/**
 * A text's outline is drawn in the edge color: a color of the text's own (e.g. a speaker's, issue #95) would paint over it
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SubtitlePainterEdgeTest {
    @Test
    public void colorsOfTheText_areLeftOutOfItsOutline() {
        SpannableStringBuilder text = new SpannableStringBuilder("-Hello, hello!\n -Hello!");
        text.setSpan(new ForegroundColorSpan(0xFF00FF00), 15, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new StyleSpan(Typeface.ITALIC), 0, 14, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        CharSequence edge = SubtitlePainter.withoutTextColors(text);

        assertNotSame(text, edge);
        assertEquals(text.toString(), edge.toString());
        assertEquals(0, ((Spanned) edge).getSpans(0, edge.length(), ForegroundColorSpan.class).length);
        // Italics change the outline's shape: kept
        assertEquals(1, ((Spanned) edge).getSpans(0, edge.length(), StyleSpan.class).length);
        // The text drawn over the outline keeps its color
        assertEquals(1, text.getSpans(0, text.length(), ForegroundColorSpan.class).length);
    }

    @Test
    public void textWithoutColors_isItsOwnOutline() {
        SpannableStringBuilder italic = new SpannableStringBuilder("The county of Somerset is beautiful,");
        italic.setSpan(new StyleSpan(Typeface.ITALIC), 0, italic.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String plain = "And it was.";

        assertSame(italic, SubtitlePainter.withoutTextColors(italic));
        assertSame(plain, SubtitlePainter.withoutTextColors(plain));
    }
}
