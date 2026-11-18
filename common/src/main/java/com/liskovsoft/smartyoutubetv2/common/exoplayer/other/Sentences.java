package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.text.Spanned;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.text.Cue;
import com.google.android.exoplayer2.text.Subtitle;
import com.google.android.exoplayer2.text.WordTimeSpan;
import com.google.android.exoplayer2.util.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Subtitles one sentence at a time (see SentenceSubtitles): the sentences of a whole subtitle file, each shown in full from when
 * it starts to be said until the next one starts, or a little after it's said when a pause follows.<br/>
 * The text is put back together first: YouTube's automatic captions show each line twice (as the bottom line, then as the top one
 * above the next), and overlapping cues show together. The words of automatic captions carry their own times (see WordTimeSpan).
 * Other subtitles only have the times of their lines, so a sentence that starts within a line starts as far into the line's time
 * as it is into its text.<br/>
 * Automatic captions without punctuation have no sentences to go by: they're cut at the pauses instead. A sentence too long to
 * read at a glance is cut too, at a pause, a comma or near its middle.
 */
final class Sentences {
    // About three lines at the default size (a line holds about 44 characters)
    static final int MAX_CHARS = 120;
    // The shortest part of a long sentence cut in two
    static final int MIN_PART_CHARS = 20;
    // Between the starts of two words: the speaker paused in between
    static final long PAUSE_US = 1_000_000;
    // A sentence isn't shown through this long a silence within it (e.g. music between its words), punctuated or not
    static final long SILENCE_US = 3_000_000;
    // A word ends where the next one starts. Before a pause that's seconds later.
    static final long MAX_WORD_US = 1_000_000;
    // How long a sentence stays once it's said, unless the next one starts sooner
    static final long LINGER_US = 1_500_000;
    // Of a line still shown at the subtitle's last event, which has no end
    static final long LAST_LINE_US = 3_000_000;
    // Punctuated text has a sentence end at least every so many characters, on average
    static final int PUNCTUATED_CHARS = 300;
    private static final String ENDS = ".!?\u2026";
    // These need no space after them
    private static final String WIDE_ENDS = "\u3002\uFF01\uFF1F";
    private static final String CLOSERS = "\"'\u201D\u2019\u00BB)]\u300D\u300F";
    private static final String OPENERS = "\"'\u201C\u2018\u00AB([\u300C\u300E";
    private static final String CLAUSE_ENDS = ",;:\u2014\u2013\u3001\uFF0C\uFF1B\uFF1A";
    // Followed by a name, so by a capital letter as a new sentence is
    private static final Set<String> ABBREVIATIONS = new HashSet<>(Arrays.asList(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "mt", "vs", "lt", "col", "gen", "sgt", "capt", "rev", "hon"));

    /**
     * A sentence's cue, which SubtitleManager shows as it is
     */
    static final class SentenceCue extends Cue {
        SentenceCue(CharSequence text) {
            super(text);
        }
    }

    /**
     * The sentences as a subtitle: each from its start to its end, none at once
     */
    static final class Timeline implements Subtitle {
        private final long[] mStartsUs;
        private final long[] mEndsUs;
        private final Cue[] mCues;
        private final long[] mEventTimesUs;

        Timeline(long[] startsUs, long[] endsUs, Cue[] cues) {
            mStartsUs = startsUs;
            mEndsUs = endsUs;
            mCues = cues;

            // A sentence's end is the next one's start, unless a pause comes between
            long[] eventTimesUs = new long[startsUs.length * 2];
            int count = 0;

            for (int i = 0; i < startsUs.length; i++) {
                eventTimesUs[count++] = startsUs[i];

                if (i + 1 == startsUs.length || endsUs[i] < startsUs[i + 1]) {
                    eventTimesUs[count++] = endsUs[i];
                }
            }

            mEventTimesUs = Arrays.copyOf(eventTimesUs, count);
        }

        int getSentenceCount() {
            return mCues.length;
        }

        @Override
        public int getNextEventTimeIndex(long timeUs) {
            int index = Util.binarySearchCeil(mEventTimesUs, timeUs, /* inclusive= */ false, /* stayInBounds= */ false);
            return index < mEventTimesUs.length ? index : C.INDEX_UNSET;
        }

        @Override
        public int getEventTimeCount() {
            return mEventTimesUs.length;
        }

        @Override
        public long getEventTime(int index) {
            return mEventTimesUs[index];
        }

        @Override
        public List<Cue> getCues(long timeUs) {
            int index = Util.binarySearchFloor(mStartsUs, timeUs, /* inclusive= */ true, /* stayInBounds= */ false);

            if (index < 0 || timeUs >= mEndsUs[index]) {
                return Collections.emptyList();
            }

            return Collections.singletonList(mCues[index]);
        }
    }

    /**
     * What the subtitle shows between two of its events: its cues' text on one line, with the times of its words
     */
    private static final class Shown {
        final String text;
        // Where the words that have their own time start in the text, ascending, and those times
        final int[] wordStarts;
        final long[] wordTimesUs;
        final long startUs;
        final long endUs;

        Shown(String text, int[] wordStarts, long[] wordTimesUs, long startUs, long endUs) {
            this.text = text;
            this.wordStarts = wordStarts;
            this.wordTimesUs = wordTimesUs;
            this.startUs = startUs;
            this.endUs = endUs;
        }
    }

    /**
     * A run of new text, said from startUs to endUs
     */
    private static final class Piece {
        final String text;
        final int[] wordStarts;
        final long[] wordTimesUs;
        final long startUs;
        // Moved on while the text is shown without anything new
        long endUs;

        Piece(String text, int[] wordStarts, long[] wordTimesUs, long startUs, long endUs) {
            this.text = text;
            this.wordStarts = wordStarts;
            this.wordTimesUs = wordTimesUs;
            this.startUs = startUs;
            this.endUs = endUs;
        }

        boolean hasWordTimes() {
            return wordStarts.length > 0;
        }

        /**
         * As far into the time as the position is into the text
         */
        long timeAt(int position) {
            int length = Math.max(1, text.length());
            return startUs + (endUs - startUs) * Math.max(0, Math.min(position, length)) / length;
        }
    }

    private Sentences() {
    }

    /**
     * @return null when the subtitle has no text
     */
    @Nullable
    static Timeline build(@NonNull Subtitle subtitle) {
        List<Piece> pieces = toPieces(readShown(subtitle));
        return pieces.isEmpty() ? null : new Builder(pieces).build();
    }

    private static List<Shown> readShown(Subtitle subtitle) {
        int count = subtitle.getEventTimeCount();
        long[] timesUs = new long[count];
        int distinct = 0;

        for (int i = 0; i < count; i++) {
            long timeUs = subtitle.getEventTime(i);

            // A cue's end is often the next one's start
            if (distinct == 0 || timeUs > timesUs[distinct - 1]) {
                timesUs[distinct++] = timeUs;
            }
        }

        List<Shown> result = new ArrayList<>(distinct);

        for (int i = 0; i < distinct; i++) {
            long endUs = i + 1 < distinct ? timesUs[i + 1] : timesUs[i] + LAST_LINE_US;
            result.add(read(subtitle.getCues(timesUs[i]), timesUs[i], endUs));
        }

        return result;
    }

    private static Shown read(List<Cue> cues, long startUs, long endUs) {
        StringBuilder raw = new StringBuilder();
        List<Integer> wordStarts = new ArrayList<>();
        List<Long> wordTimesUs = new ArrayList<>();

        for (Cue cue : cues) {
            CharSequence text = cue.text;

            if (text == null || text.length() == 0) {
                continue;
            }

            if (raw.length() > 0) {
                raw.append(' ');
            }

            int base = raw.length();
            raw.append(text);

            if (text instanceof Spanned) {
                Spanned spanned = (Spanned) text;

                for (WordTimeSpan span : spanned.getSpans(0, spanned.length(), WordTimeSpan.class)) {
                    wordStarts.add(base + spanned.getSpanStart(span));
                    wordTimesUs.add(span.timeUs);
                }
            }
        }

        // One space between words, none at either end. Where each raw position went:
        StringBuilder text = new StringBuilder(raw.length());
        int[] positions = new int[raw.length() + 1];

        for (int i = 0; i < raw.length(); i++) {
            positions[i] = text.length();
            char c = raw.charAt(i);

            // Manual captions pad their lines with no-break spaces, which within a sentence would show as gaps
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                if (text.length() > 0 && text.charAt(text.length() - 1) != ' ') {
                    text.append(' ');
                }
            } else {
                text.append(c);
            }
        }

        positions[raw.length()] = text.length();

        if (text.length() > 0 && text.charAt(text.length() - 1) == ' ') {
            text.setLength(text.length() - 1);
        }

        // The spans start at the space before their word, in order of the words, but that's not promised
        long[][] words = new long[wordStarts.size()][];

        for (int i = 0; i < words.length; i++) {
            words[i] = new long[] {positions[wordStarts.get(i)], wordTimesUs.get(i)};
        }

        Arrays.sort(words, (a, b) -> Long.compare(a[0], b[0]));
        int[] starts = new int[words.length];
        long[] times = new long[words.length];
        int count = 0;

        for (long[] word : words) {
            int start = (int) word[0];

            if (start < text.length() && (count == 0 || start > starts[count - 1])) {
                starts[count] = start;
                times[count] = word[1];
                count++;
            }
        }

        return new Shown(text.toString(), Arrays.copyOf(starts, count), Arrays.copyOf(times, count), startUs, endUs);
    }

    /**
     * The text as said, each part once
     */
    private static List<Piece> toPieces(List<Shown> shownList) {
        // Automatic captions: their lines roll on across a long pause too
        boolean isRolling = false;

        for (Shown shown : shownList) {
            if (shown.wordStarts.length > 0) {
                isRolling = true;
                break;
            }
        }

        List<Piece> pieces = new ArrayList<>();
        String previous = "";
        String previousNew = "";

        for (Shown shown : shownList) {
            if (shown.text.isEmpty()) {
                if (!isRolling) {
                    // E.g. the same line said again after a while
                    previous = "";
                    previousNew = "";
                }

                continue;
            }

            int start = newTextStart(shown.text, previous, previousNew);
            previous = shown.text;

            if (start >= shown.text.length()) {
                if (!pieces.isEmpty()) {
                    // Still shown, so still said
                    Piece last = pieces.get(pieces.size() - 1);
                    last.endUs = Math.max(last.endUs, shown.endUs);
                }

                continue;
            }

            Piece piece = toPiece(shown, start);
            previousNew = piece.text;
            pieces.add(piece);
        }

        return pieces;
    }

    /**
     * Where the new text starts in what's shown: past the text shown before (automatic captions show the line before above the new
     * one, overlapping cues show the earlier one with the later), or past the part of it that was new. The text's length when
     * nothing in it is new (the earlier of overlapping cues has ended).
     */
    static int newTextStart(String text, String previous, String previousNew) {
        if (startsWithWords(text, previous)) {
            return Math.min(text.length(), previous.length() + 1);
        }

        if (startsWithWords(text, previousNew)) {
            return Math.min(text.length(), previousNew.length() + 1);
        }

        if (endsWithWords(previous, text)) {
            return text.length();
        }

        return 0;
    }

    private static boolean startsWithWords(String text, String prefix) {
        return !prefix.isEmpty() && text.startsWith(prefix) && (text.length() == prefix.length() || text.charAt(prefix.length()) == ' ');
    }

    private static boolean endsWithWords(String text, String suffix) {
        return !suffix.isEmpty() && text.endsWith(suffix)
                && (text.length() == suffix.length() || text.charAt(text.length() - suffix.length() - 1) == ' ');
    }

    private static Piece toPiece(Shown shown, int start) {
        int[] wordStarts = new int[shown.wordStarts.length];
        long[] wordTimesUs = new long[shown.wordTimesUs.length];
        int count = 0;

        for (int i = 0; i < shown.wordStarts.length; i++) {
            // A span starts at the space before its word, which is just before the new text
            if (shown.wordStarts[i] >= start - 1) {
                int wordStart = Math.max(0, shown.wordStarts[i] - start);

                if (count == 0 || wordStart > wordStarts[count - 1]) {
                    wordStarts[count] = wordStart;
                    wordTimesUs[count] = shown.wordTimesUs[i];
                    count++;
                }
            }
        }

        return new Piece(shown.text.substring(start), Arrays.copyOf(wordStarts, count), Arrays.copyOf(wordTimesUs, count),
                shown.startUs, shown.endUs);
    }

    /**
     * The sentences of the pieces put together
     */
    private static final class Builder {
        private final List<Piece> mPieces;
        private final int[] mOffsets;
        private final String mText;

        Builder(List<Piece> pieces) {
            mPieces = pieces;
            mOffsets = new int[pieces.size()];
            StringBuilder text = new StringBuilder();

            for (int i = 0; i < pieces.size(); i++) {
                if (text.length() > 0) {
                    text.append(' ');
                }

                mOffsets[i] = text.length();
                text.append(pieces.get(i).text);
            }

            mText = text.toString();
        }

        Timeline build() {
            List<int[]> ranges = new ArrayList<>();
            int ends = splitAtEnds(ranges);
            boolean isPunctuated = ends * PUNCTUATED_CHARS >= mText.length();
            List<int[]> parts = new ArrayList<>();

            for (int[] range : ranges) {
                cutAtPauses(range[0], range[1], isPunctuated ? SILENCE_US : PAUSE_US, parts);
            }

            // Said at once with the one before (the same word's time): shown with it
            List<int[]> sentences = new ArrayList<>();
            List<Long> startsUs = new ArrayList<>();

            for (int[] part : parts) {
                long startUs = startUsAt(part[0]);

                if (!sentences.isEmpty() && startUs <= startsUs.get(startsUs.size() - 1)) {
                    sentences.get(sentences.size() - 1)[1] = part[1];
                    continue;
                }

                sentences.add(part);
                startsUs.add(startUs);
            }

            int count = sentences.size();
            long[] starts = new long[count];
            long[] endsUs = new long[count];
            Cue[] cues = new Cue[count];

            for (int i = 0; i < count; i++) {
                int[] sentence = sentences.get(i);
                starts[i] = startsUs.get(i);
                long endUs = saidUsAt(sentence[1] - 1) + LINGER_US;

                if (i + 1 < count) {
                    endUs = Math.min(endUs, startsUs.get(i + 1));
                }

                // The next one starts later, so there's room
                endsUs[i] = Math.max(endUs, starts[i] + 1);
                cues[i] = new SentenceCue(mText.substring(sentence[0], sentence[1]));
            }

            return new Timeline(starts, endsUs, cues);
        }

        /**
         * Cuts the text after each sentence's end: a full stop, a question or exclamation mark, an ellipsis (with any closing quotes
         * or brackets after them) and a space. Not after an abbreviation or an initial, nor before a word in lower case.
         *
         * @return how many ends there were
         */
        private int splitAtEnds(List<int[]> out) {
            int length = mText.length();
            int start = 0;
            int ends = 0;

            for (int i = 0; i < length; i++) {
                char c = mText.charAt(i);
                boolean isWide = WIDE_ENDS.indexOf(c) >= 0;

                if (!isWide && ENDS.indexOf(c) < 0) {
                    continue;
                }

                int markStart = i;
                boolean isDotsOnly = c == '.' || c == '\u2026';
                int end = i + 1;

                // E.g. ?! or ... or a closing quote
                while (end < length) {
                    char next = mText.charAt(end);

                    if (ENDS.indexOf(next) >= 0 || WIDE_ENDS.indexOf(next) >= 0) {
                        isWide |= WIDE_ENDS.indexOf(next) >= 0;
                        isDotsOnly &= next == '.' || next == '\u2026';
                    } else if (CLOSERS.indexOf(next) < 0) {
                        break;
                    }

                    end++;
                }

                i = end - 1;

                // E.g. 3.5 or youtube.com
                if (!isWide && end < length && mText.charAt(end) != ' ') {
                    continue;
                }

                if (isDotsOnly && (isAbbreviation(markStart) || isLowerCaseNext(end))) {
                    continue;
                }

                addTrimmed(start, end, out);
                start = end;
                ends++;
            }

            addTrimmed(start, length, out);

            return ends;
        }

        private boolean isAbbreviation(int dotPosition) {
            int start = mText.lastIndexOf(' ', dotPosition - 1) + 1;

            while (start < dotPosition && OPENERS.indexOf(mText.charAt(start)) >= 0) {
                start++;
            }

            String word = mText.substring(start, dotPosition);

            if (word.isEmpty()) {
                return false;
            }

            // An initial, or e.g. U.S.
            if ((word.length() == 1 && Character.isLetter(word.charAt(0))) || word.indexOf('.') >= 0) {
                return true;
            }

            return ABBREVIATIONS.contains(word.toLowerCase(Locale.ROOT));
        }

        private boolean isLowerCaseNext(int position) {
            while (position < mText.length() && (mText.charAt(position) == ' ' || OPENERS.indexOf(mText.charAt(position)) >= 0)) {
                position++;
            }

            return position < mText.length() && Character.isLowerCase(mText.charAt(position));
        }

        private void addTrimmed(int start, int end, List<int[]> out) {
            while (start < end && mText.charAt(start) == ' ') {
                start++;
            }

            while (end > start && mText.charAt(end - 1) == ' ') {
                end--;
            }

            if (end > start) {
                out.add(new int[] {start, end});
            }
        }

        /**
         * Cuts at each pause at least that long (any pause, in text without punctuation), then as long sentences are
         */
        private void cutAtPauses(int start, int end, long minPauseUs, List<int[]> out) {
            int partStart = start;

            for (int i = start + 1; i < end - 1; i++) {
                if (mText.charAt(i) == ' ' && pauseUsAt(i) >= minPauseUs) {
                    cutLong(partStart, i, out);
                    partStart = i + 1;
                }
            }

            cutLong(partStart, end, out);
        }

        /**
         * Cuts a sentence too long to read at a glance, at the space after the longest pause, else after the comma nearest its
         * middle, else nearest its middle
         */
        private void cutLong(int start, int end, List<int[]> out) {
            if (end - start <= MAX_CHARS) {
                addTrimmed(start, end, out);
                return;
            }

            int middle = (start + end) / 2;
            int pauseAt = -1;
            long longestPauseUs = PAUSE_US - 1;
            int clauseAt = -1;
            int anyAt = -1;

            for (int i = start + MIN_PART_CHARS; i < end - MIN_PART_CHARS; i++) {
                if (mText.charAt(i) != ' ') {
                    continue;
                }

                long pauseUs = pauseUsAt(i);

                if (pauseUs > longestPauseUs) {
                    longestPauseUs = pauseUs;
                    pauseAt = i;
                }

                char before = mText.charAt(i - 1);
                char after = mText.charAt(i + 1);

                if ((CLAUSE_ENDS.indexOf(before) >= 0 || CLAUSE_ENDS.indexOf(after) >= 0)
                        && (clauseAt == -1 || Math.abs(i - middle) < Math.abs(clauseAt - middle))) {
                    clauseAt = i;
                }

                if (anyAt == -1 || Math.abs(i - middle) < Math.abs(anyAt - middle)) {
                    anyAt = i;
                }
            }

            int at = pauseAt != -1 ? pauseAt : clauseAt != -1 ? clauseAt : anyAt;

            if (at == -1) {
                // One long word, or text without spaces
                addTrimmed(start, end, out);
                return;
            }

            cutLong(start, at, out);
            cutLong(at + 1, end, out);
        }

        /**
         * From the start of the word before the space to the start of the word after it. None within a line whose words have no
         * times of their own: those are only guessed from where in the line they are.
         */
        private long pauseUsAt(int space) {
            int piece = pieceAt(space - 1);

            if (piece == pieceAt(space + 1) && !mPieces.get(piece).hasWordTimes()) {
                return 0;
            }

            return startUsAt(space + 1) - startUsAt(space - 1);
        }

        /**
         * The piece the position is in: on the space between two, the next one
         */
        private int pieceAt(int position) {
            int index = Util.binarySearchFloor(mOffsets, position, /* inclusive= */ true, /* stayInBounds= */ true);

            if (index + 1 < mPieces.size() && position >= mOffsets[index] + mPieces.get(index).text.length()) {
                index++;
            }

            return index;
        }

        /**
         * When the word at the position starts to be said
         */
        private long startUsAt(int position) {
            int index = pieceAt(position);
            Piece piece = mPieces.get(index);
            int inPiece = Math.max(0, position - mOffsets[index]);

            if (piece.hasWordTimes()) {
                int word = Util.binarySearchFloor(piece.wordStarts, inPiece, /* inclusive= */ true, /* stayInBounds= */ false);
                return word >= 0 ? piece.wordTimesUs[word] : piece.startUs;
            }

            return piece.timeAt(inPiece);
        }

        /**
         * When the text up to the position (inclusive) has been said
         */
        private long saidUsAt(int position) {
            int index = pieceAt(position);
            Piece piece = mPieces.get(index);
            int inPiece = Math.max(0, position - mOffsets[index]);

            if (piece.hasWordTimes()) {
                int word = Util.binarySearchFloor(piece.wordStarts, inPiece, /* inclusive= */ true, /* stayInBounds= */ false);
                long wordStartUs = word >= 0 ? piece.wordTimesUs[word] : piece.startUs;
                long nextWordStartUs = word + 1 < piece.wordStarts.length ? piece.wordTimesUs[word + 1] : piece.endUs;
                return Math.max(wordStartUs, Math.min(nextWordStartUs, wordStartUs + MAX_WORD_US));
            }

            return piece.timeAt(inPiece + 1);
        }
    }
}
