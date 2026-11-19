package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.SimpleSubtitleDecoder;
import com.google.android.exoplayer2.text.Subtitle;
import com.google.android.exoplayer2.text.SubtitleDecoder;
import com.google.android.exoplayer2.text.SubtitleDecoderFactory;
import com.google.android.exoplayer2.text.SubtitleInputBuffer;
import com.google.android.exoplayer2.text.SubtitleOutputBuffer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Captions in the colors, italics and places their author gave them (issue #95). The cues are YouTube's, as it served them on
 * 2026-11-19 (see YouTubeWebvttStylesTest).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StyledCaptionsTest {
    private static final String STYLED =
            "WEBVTT\nKind: captions\nLanguage: en\nStyle:\n::cue(c.green) { color: green; }\n::cue(c.yellow) { color: yellow; }\n##\n\n"
                    + "00:00:04.200 --> 00:00:05.000 line:85%\nAnd it was.\n\n"
                    + "00:00:07.160 --> 00:00:11.760 position:56% line:91%\n<i>The county of Somerset is beautiful,\nall rolling hills and coastline.</i>\n\n"
                    + "00:00:23.360 --> 00:00:24.680 size:44% position:22% line:91%\n-Hello, hello!\n<c.green> -Hello!</c>\n\n"
                    + "00:00:39.680 --> 00:00:41.280 line:85%\n<c.yellow>Yeah, and possibly earlier.</c>\n\n";
    // Stonehenge's captions: a line at the top while something's shown at the bottom
    private static final String TOP =
            "WEBVTT\nKind: captions\nLanguage: en\n\n"
                    + "00:00:03.280 --> 00:00:05.680 position:63% line:85%\nand that’s actually part of the problem.\n\n"
                    + "00:00:05.720 --> 00:00:08.280 line:14%\nHello Stonehenge!\n\n";
    // Without any: the subtitle position setting places them as before
    private static final String PLAIN =
            "WEBVTT\nKind: captions\nLanguage: en\n\n"
                    + "00:00:00.000 --> 00:00:03.720 line:91%\nI thought that carrying a giant\nwas going to be difficult.\n\n"
                    + "00:00:04.200 --> 00:00:05.000 line:85%\nAnd it was.\n\n";
    // Automatic captions: the times of the words, and nothing else
    private static final String AUTOMATIC =
            "WEBVTT\nKind: captions\nLanguage: en\n\n"
                    + "00:00:00.160 --> 00:00:02.470 align:start position:0%\n \n"
                    + "I<00:00:00.480><c> thought</c><00:00:00.640><c> that</c><00:00:00.960><c> carrying</c>\n\n"
                    + "00:00:02.470 --> 00:00:02.480 align:start position:0%\nI thought that carrying a giant was\n \n\n";
    // A live stream's (see LiveCaptionChunkTest): low on the screen, as they're shown now
    private static final String LIVE =
            "WEBVTT\nKind: captions\n\n"
                    + "00:00:00.000 --> 00:00:02.000 align:start position:0% line:68%\nREPORTING FROM BANGOR\n\n";

    @Test
    public void filesWithColorsItalicsOrTopLines_areStyled() throws Exception {
        assertTrue(StyledCaptions.isStyled(decode(STYLED)));
        assertTrue(StyledCaptions.isStyled(decode(TOP)));
    }

    @Test
    public void plainAutomaticAndLiveCaptions_aren_tStyled() throws Exception {
        assertFalse(StyledCaptions.isStyled(decode(PLAIN)));
        assertFalse(StyledCaptions.isStyled(decode(AUTOMATIC)));
        assertFalse(StyledCaptions.isStyled(decode(LIVE)));
    }

    @Test
    public void styledCue_keepsItsColorsAndItalics_inTheMiddleAtTheBottom() throws Exception {
        Subtitle subtitle = decode(STYLED);
        List<Cue> cues = subtitle.getCues(24_000_000);
        assertTrue(StyledCaptions.containsStyled(cues));

        List<Cue> placed = StyledCaptions.place(cues);

        assertEquals(1, placed.size());
        Cue cue = placed.get(0);
        assertEquals("-Hello, hello!\n -Hello!", cue.text.toString());
        assertEquals(1, ((Spanned) cue.text).getSpans(0, cue.text.length(), ForegroundColorSpan.class).length);
        // Not where YouTube's WebVTT says (position:22%, size:44%): its own player shows it in the middle
        assertEquals(Cue.DIMEN_UNSET, cue.line, 0);
        assertEquals(Cue.DIMEN_UNSET, cue.position, 0);
        assertEquals(Cue.DIMEN_UNSET, cue.size, 0);

        Cue italic = StyledCaptions.place(subtitle.getCues(8_000_000)).get(0);
        assertEquals(1, ((Spanned) italic.text).getSpans(0, italic.text.length(), StyleSpan.class).length);
    }

    @Test
    public void topCue_staysAtTheTop() throws Exception {
        List<Cue> cues = decode(TOP).getCues(6_000_000);
        assertTrue(StyledCaptions.containsStyled(cues));

        List<Cue> placed = StyledCaptions.place(cues);

        assertEquals(1, placed.size());
        assertEquals("Hello Stonehenge!", placed.get(0).text.toString());
        assertEquals(0.14f, placed.get(0).line, 0.0001f);
        assertEquals(Cue.LINE_TYPE_FRACTION, placed.get(0).lineType);
        assertEquals(Cue.DIMEN_UNSET, placed.get(0).position, 0);
    }

    @Test
    public void cuesShownAtOnce_areDrawnTogether() throws Exception {
        List<Cue> cues = decode(TOP + "\n").getCues(6_000_000);
        List<Cue> both = Arrays.asList(
                decode(STYLED).getCues(40_000_000).get(0), // yellow, at the bottom
                decode(PLAIN).getCues(1_000_000).get(0), // plain, at the bottom
                cues.get(0)); // at the top

        List<Cue> placed = StyledCaptions.place(both);

        assertEquals(2, placed.size());
        assertEquals("Hello Stonehenge!", placed.get(0).text.toString());
        // A cue without styles has its lines joined, as before (see WebvttCueParser)
        assertEquals("Yeah, and possibly earlier.\nI thought that carrying a giant was going to be difficult.", placed.get(1).text.toString());
        Spanned bottom = (Spanned) placed.get(1).text;
        ForegroundColorSpan[] colors = bottom.getSpans(0, bottom.length(), ForegroundColorSpan.class);
        assertEquals(1, colors.length);
        assertEquals(0, bottom.getSpanStart(colors[0]));
        assertEquals("Yeah, and possibly earlier.".length(), bottom.getSpanEnd(colors[0]));
    }

    @Test
    public void plainCues_aren_tStyled() throws Exception {
        assertFalse(StyledCaptions.containsStyled(decode(PLAIN).getCues(1_000_000)));
        assertFalse(StyledCaptions.containsStyled(decode(AUTOMATIC).getCues(1_000_000)));
        assertFalse(StyledCaptions.containsStyled(decode(STYLED).getCues(4_500_000)));
    }

    @Test
    public void decodedTrack_isToldAsStyled_untilTheNextTrack() throws Exception {
        StyledCaptions.onTrackChanged();
        assertFalse(StyledCaptions.isTrackStyled());

        StyledCaptions.onFileDecoded(decode(STYLED));
        assertTrue(StyledCaptions.isTrackStyled());

        StyledCaptions.onTrackChanged();
        assertFalse(StyledCaptions.isTrackStyled());

        StyledCaptions.onFileDecoded(decode(PLAIN));
        assertFalse(StyledCaptions.isTrackStyled());
    }

    /**
     * As the player's decoder hands it to SentenceSubtitles
     */
    private static Subtitle decode(String webvtt) throws Exception {
        Format format = Format.createTextSampleFormat(null, MimeTypes.TEXT_VTT, 0, "en");
        SubtitleDecoder decoder = SubtitleDecoderFactory.DEFAULT.createDecoder(format);
        Subtitle[] decoded = new Subtitle[1];
        ((SimpleSubtitleDecoder) decoder).setTransformer(subtitle -> {
            decoded[0] = subtitle;
            return subtitle;
        });

        try {
            SubtitleInputBuffer input = decoder.dequeueInputBuffer();
            byte[] bytes = Util.getUtf8Bytes(webvtt);
            input.ensureSpaceForWrite(bytes.length);
            input.data.put(bytes);
            input.data.flip();
            input.timeUs = 0;
            decoder.queueInputBuffer(input);

            SubtitleOutputBuffer output = null;

            for (int i = 0; i < 200 && output == null; i++) {
                output = decoder.dequeueOutputBuffer();

                if (output == null) {
                    Thread.sleep(5);
                }
            }

            if (output == null || decoded[0] == null) {
                throw new AssertionError("Not decoded");
            }

            return decoded[0];
        } finally {
            decoder.release();
        }
    }
}
