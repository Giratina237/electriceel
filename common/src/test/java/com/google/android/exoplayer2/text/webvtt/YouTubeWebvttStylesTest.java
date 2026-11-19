package com.google.android.exoplayer2.text.webvtt;

import android.graphics.Typeface;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SubtitleCues;
import com.google.android.exoplayer2.text.WordTimeSpan;
import com.google.android.exoplayer2.util.Util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * YouTube's WebVTT has its cues' styles in the header (issue #95). From the captions of Tom Scott's "The chances of being surprised
 * by a giant are low, but not zero." (sdORrH7d2ug) and "Why you can't touch Stonehenge any more" (0V-4lQYNugA), as YouTube served
 * them on 2026-11-19.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class YouTubeWebvttStylesTest {
    private static final String HEADER =
            "WEBVTT\nKind: captions\nLanguage: en\nStyle:\n"
                    + "::cue(c.colorDDDDDD) { color: rgb(221,221,221);\n }\n"
                    + "::cue(c.cyan) { color: cyan; }\n"
                    + "::cue(c.green) { color: green; }\n"
                    + "::cue(c.yellow) { color: yellow; }\n"
                    + "##\n\n";
    private static final String CUES =
            "00:00:04.200 --> 00:00:05.000 line:85%\nAnd it was.\n\n"
                    + "00:00:05.040 --> 00:00:06.480 line:85%\n<c.colorDDDDDD>(sea roaring)</c>\n\n"
                    + "00:00:07.160 --> 00:00:11.760 position:56% line:91%\n<i>The county of Somerset is beautiful,\nall rolling hills and coastline.</i>\n\n"
                    + "00:00:23.360 --> 00:00:24.680 size:44% position:22% line:91%\n-Hello, hello!\n<c.green> -Hello!</c>\n\n"
                    + "00:00:39.680 --> 00:00:41.280 line:85%\n<c.yellow>Yeah, and possibly earlier.</c>\n\n"
                    + "00:17:56.000 --> 00:17:58.600 line:91%\nThe weight is fine.\n<c.cyan> -Yeah, it’s the balance.</c>\n\n"
                    + "00:18:05.720 --> 00:18:08.280 line:14%\nHello Stonehenge!\n\n";

    @Test
    public void headerStyles_colorTheirClasses() throws Exception {
        List<WebvttCue> cues = decode(HEADER + CUES);

        assertEquals(0xFFDDDDDD, colorOf(cues.get(1), "(sea roaring)"));
        assertEquals(0xFFFFFF00, colorOf(cues.get(4), "Yeah, and possibly earlier."));
        assertEquals(0xFF00FFFF, colorOf(cues.get(5), " -Yeah, it’s the balance."));
    }

    @Test
    public void youTubesGreen_isTheBrightOne() throws Exception {
        List<WebvttCue> cues = decode(HEADER + CUES);

        // As YouTube's own player and srv3 captions have it, not CSS's #008000
        assertEquals(0xFF00FF00, colorOf(cues.get(3), " -Hello!"));
        // The other speaker's line has no color of its own
        assertEquals(0, ((Spanned) cues.get(3).text).getSpans(0, "-Hello, hello!".length(), ForegroundColorSpan.class).length);
    }

    @Test
    public void italicsAndLines_areKept() throws Exception {
        List<WebvttCue> cues = decode(HEADER + CUES);

        StyleSpan[] italics = ((Spanned) cues.get(2).text).getSpans(0, cues.get(2).text.length(), StyleSpan.class);
        assertEquals(1, italics.length);
        assertEquals(Typeface.ITALIC, italics[0].getStyle());
        assertEquals(0.14f, cues.get(6).line, 0.0001f);
        assertEquals(Cue.LINE_TYPE_FRACTION, cues.get(6).lineType);
    }

    @Test
    public void headerEndingWithinTheStyles_stillHasItsCues() throws Exception {
        String header = "WEBVTT\nKind: captions\nStyle:\n::cue(c.yellow) { color: yellow; }\n\n";

        List<WebvttCue> cues = decode(header + CUES);

        assertEquals(7, cues.size());
        assertEquals(0xFFFFFF00, colorOf(cues.get(4), "Yeah, and possibly earlier."));
    }

    @Test
    public void headerWithoutStyles_isSkippedAsBefore() throws Exception {
        List<WebvttCue> cues = decode("WEBVTT\nKind: captions\nLanguage: en\n\n" + CUES);

        assertEquals(7, cues.size());
        assertEquals("And it was.", cues.get(0).text.toString());
        assertEquals(0, ((Spanned) cues.get(4).text).getSpans(0, cues.get(4).text.length(), ForegroundColorSpan.class).length);
    }

    @Test
    public void styledCue_keepsItsLines_andDecodesItsEntities() throws Exception {
        List<WebvttCue> cues = decode(HEADER
                + "00:00:01.000 --> 00:00:02.000 line:91%\n<i>Tom &amp; Jerry&#39;s Q & A &lt;3</i>\n<c.yellow>&quot;Yes&quot;</c>\n\n");

        assertEquals("Tom & Jerry's Q & A <3\n\"Yes\"", cues.get(0).text.toString());
        assertEquals(0xFFFFFF00, colorOf(cues.get(0), "\"Yes\""));
    }

    @Test
    public void cuesWithoutStyles_areReadAsBefore() throws Exception {
        List<WebvttCue> cues = decode(HEADER
                + "00:00:00.000 --> 00:00:03.720 line:91%\nI thought that carrying a giant\nwas going to be difficult.\n\n"
                + "00:00:00.160 --> 00:00:02.470 align:start position:0%\n \nI<00:00:00.480><c> thought</c><00:00:00.640><c> that</c>\n\n");

        // Html.fromHtml joins the lines (see WebvttCueParser)
        assertEquals("I thought that carrying a giant was going to be difficult.", cues.get(0).text.toString());

        // Automatic captions: the times of their words
        Spanned automatic = (Spanned) cues.get(1).text;
        assertEquals("I thought that", automatic.toString().trim());
        WordTimeSpan[] words = automatic.getSpans(0, automatic.length(), WordTimeSpan.class);
        assertEquals(2, words.length);
        assertEquals(480_000, words[0].timeUs);
        assertEquals("thought", automatic.subSequence(automatic.getSpanStart(words[0]), automatic.getSpanEnd(words[0])).toString().trim());
        assertEquals(640_000, words[1].timeUs);
        assertEquals("that", automatic.subSequence(automatic.getSpanStart(words[1]), automatic.getSpanEnd(words[1])).toString().trim());
    }

    private static List<WebvttCue> decode(String webvtt) throws Exception {
        byte[] bytes = Util.getUtf8Bytes(webvtt);
        WebvttSubtitle subtitle = new WebvttDecoder().decode(bytes, bytes.length, false);

        @SuppressWarnings("unchecked")
        List<WebvttCue> cues = (List<WebvttCue>) ((SubtitleCues) subtitle).getAllCues();

        return cues;
    }

    /**
     * The color of the part of the cue's text, which has to be whole
     */
    private static int colorOf(Cue cue, String part) {
        Spanned text = (Spanned) cue.text;
        int start = text.toString().indexOf(part);
        assertTrue(text + " has " + part, start >= 0);
        ForegroundColorSpan[] spans = text.getSpans(start, start + part.length(), ForegroundColorSpan.class);
        assertEquals(1, spans.length);
        assertEquals(start, text.getSpanStart(spans[0]));
        assertEquals(start + part.length(), text.getSpanEnd(spans[0]));

        return spans[0].getForegroundColor();
    }
}
