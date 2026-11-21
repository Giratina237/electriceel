package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import android.content.Context;
import android.content.res.Configuration;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.smartyoutubetv2.common.R;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the AI of the settings search is sent: the settings (the same each time, so the provider can cache them), then the values
 * of the settings and the question.<br/>
 * Only the texts of the app itself are sent: a text of a setting that isn't one of the app's strings (e.g. the name of an
 * account, a channel, a device, a server address) is left out. A string with a number in it (e.g. "Speed: 1.25x") is sent when
 * the number is all that's added to the app's string.
 */
public final class AiPrompt {
    /**
     * At most this many settings picked
     */
    public static final int MAX_PICKS = 5;
    private static final String HIDDEN = "…";
    private static final Pattern FORMAT_ARG = Pattern.compile("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfx]");
    /**
     * A number, maybe with a unit (e.g. 720p, 1.25x, 60 fps, 10 s, 25%, 1:30)
     */
    private static final Pattern NUMERIC = Pattern.compile(
            "[-+−]?[\\d.,:/×x%\\s]*\\d[\\d.,:/×x%\\s]*(px|dp|sp|ms|s|sec|min|h|kb|mb|gb|kbps|mbps|fps|p|hz|k|%|x)?",
            Pattern.CASE_INSENSITIVE);
    /**
     * An address the user typed: of a server (by IP or name), a link, an email
     */
    private static final Pattern ADDRESS = Pattern.compile(
            ".*(\\d+\\.\\d+\\.\\d+\\.\\d+|@|://|[\\p{L}\\d-]+\\.[\\p{L}\\d-]*\\p{L}[\\p{L}\\d-]*\\.\\p{L}{2,}|[\\p{L}\\d-]+\\.(com|net|org|io|dev|app|tv|me)\\b).*",
            Pattern.CASE_INSENSITIVE);
    /**
     * Names of formats and engines in the choices of the player (e.g. "0.16Mbps, opus, DRC")
     */
    private static final Set<String> TECH_WORDS = new HashSet<>(java.util.Arrays.asList(
            "avc", "vp9", "vp09", "av1", "av01", "hevc", "hev", "opus", "mp4a", "aac", "drc", "hdr", "sdr", "dv", "mbps", "kbps", "fps",
            "cronet", "okhttp", "exoplayer", "webm", "mp4", "dash", "hls", "pcm", "flac", "atmos"));
    private static Locale sLocale;
    private static Set<String> sStrings;
    /**
     * The words of the app's strings, of the names of the languages and countries, and of the AI providers
     */
    private static Set<String> sWords;
    private static List<FormatString> sFormats;
    private final Context mContext;

    public AiPrompt(Context context) {
        mContext = context;
        loadStrings(context);
    }

    /**
     * The instructions and the settings
     */
    public String getSystem(List<SettingEntry> entries) {
        StringBuilder builder = new StringBuilder();
        builder.append("You help the user of Electric Eel, a YouTube app for Android TV, find a setting of the app.\n")
                .append("You can't change settings and you don't open them: the user picks one of the settings you name, and the app ")
                .append("opens it.\n")
                .append("Answer with JSON: \"reply\" is a short answer for a TV screen, at most 3 sentences, in the language of the ")
                .append("question: which setting to open and what to pick in it, or that the app has no such setting. No markdown. ")
                .append("\"settings\" is the ids of the settings that answer the question, the best first, at most ")
                .append(MAX_PICKS)
                .append(", only ids of the list below. Empty when none does.\n")
                .append("A question that isn't about the app's settings: say in \"reply\" that you only find settings, and pick none.\n")
                .append("The texts of the settings are in the language of the app. ")
                .append(HIDDEN)
                .append(" is a private text, left out.\n\n")
                .append("The settings, one per line: id | where it is | name | kind | choices (of a list) | description\n");

        for (SettingEntry entry : entries) {
            String line = getLine(entry);

            if (line != null) {
                builder.append(line).append('\n');
            }
        }

        return builder.toString();
    }

    /**
     * The values of the settings now, then the question
     */
    public String getUser(List<SettingEntry> entries, String question) {
        StringBuilder values = new StringBuilder();

        for (SettingEntry entry : entries) {
            if (!isAllowed(entry.title)) {
                continue;
            }

            if (entry.kind == SettingEntry.KIND_TOGGLE && entry.isOn) {
                append(values, entry.id + "=on");
            } else if (entry.kind == SettingEntry.KIND_CHOICE && entry.picked != null && isAllowed(entry.picked)) {
                append(values, entry.id + "=" + clean(entry.picked));
            }
        }

        return "Now on (the switches not listed are off), and the choice picked of each list: " + values + "\n\nQuestion: " +
                question.trim();
    }

    @Nullable
    @VisibleForTesting
    String getLine(SettingEntry entry) {
        if (!isAllowed(entry.title)) {
            return null;
        }

        List<String> path = new ArrayList<>();

        for (String part : entry.path) {
            path.add(isAllowed(part) ? clean(part) : HIDDEN);
        }

        StringBuilder line = new StringBuilder();
        line.append(entry.id).append(" | ").append(TextUtils.join(" > ", path)).append(" | ").append(clean(entry.title))
                .append(" | ").append(getKindName(entry.kind));

        if (!entry.choices.isEmpty()) {
            List<String> choices = new ArrayList<>();

            for (String choice : entry.choices) {
                String text = isAllowed(choice) ? clean(choice) : HIDDEN;

                // Many private ones are one
                if (!(text.equals(HIDDEN) && !choices.isEmpty() && choices.get(choices.size() - 1).equals(HIDDEN))) {
                    choices.add(text);
                }
            }

            line.append(" | ").append(TextUtils.join("; ", choices));
        } else if (entry.description != null && isAllowed(entry.description)) {
            line.append(" | | ").append(clean(entry.description));
        }

        return line.toString();
    }

    private static String getKindName(int kind) {
        switch (kind) {
            case SettingEntry.KIND_SECTION:
                return "section";
            case SettingEntry.KIND_CARD:
                return "panel";
            case SettingEntry.KIND_CHOICE:
                return "list";
            case SettingEntry.KIND_TOGGLE:
                return "switch";
            default:
                return "button";
        }
    }

    /**
     * One of the app's strings, or one with only numbers added to it
     */
    @VisibleForTesting
    boolean isAllowed(@Nullable String text) {
        if (text == null) {
            return false;
        }

        String trimmed = text.trim();

        if (trimmed.isEmpty() || sStrings.contains(trimmed)) {
            return true;
        }

        // E.g. the address of a server the user typed
        if (ADDRESS.matcher(trimmed).matches()) {
            return false;
        }

        if (NUMERIC.matcher(trimmed).matches()) {
            return true;
        }

        for (FormatString format : sFormats) {
            if (format.matches(trimmed)) {
                return true;
            }
        }

        return hasOnlyKnownWords(trimmed);
    }

    /**
     * Made of words of the app, numbers and names of formats (e.g. "Only audio (by pressing HOME)", "1080p 60fps avc"): not a name
     * the user gave (e.g. of an account, a channel, a device)
     */
    private static boolean hasOnlyKnownWords(String text) {
        List<String> words = SettingsSearch.split(text);

        if (words.isEmpty()) {
            return false;
        }

        for (String word : words) {
            boolean isNumber = word.length() <= 8 && word.matches(".*\\d.*");

            if (!isNumber && !sWords.contains(word) && !TECH_WORDS.contains(word)) {
                return false;
            }
        }

        return true;
    }

    private static String clean(String text) {
        return text.replace('\n', ' ').replace('|', '/').trim();
    }

    private static void append(StringBuilder builder, String item) {
        if (builder.length() > 0) {
            builder.append(", ");
        }

        builder.append(item);
    }

    /**
     * The app's strings in the language of the app: read once per language
     */
    private static synchronized void loadStrings(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        Locale locale = configuration.locale;

        if (sStrings != null && locale != null && locale.equals(sLocale)) {
            return;
        }

        Set<String> strings = new HashSet<>();
        Set<String> words = new HashSet<>();
        List<FormatString> formats = new ArrayList<>();

        for (Field field : R.string.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) {
                continue;
            }

            String text;

            try {
                text = context.getString(field.getInt(null));
            } catch (Exception e) {
                continue;
            }

            text = text.trim();

            if (text.isEmpty()) {
                continue;
            }

            strings.add(text);
            words.addAll(SettingsSearch.split(text.replaceAll("%(\\d+\\$)?[a-z]", " ")));

            if (text.contains("%")) {
                FormatString format = FormatString.from(text);

                if (format != null) {
                    formats.add(format);
                }
            }
        }

        for (Locale available : Locale.getAvailableLocales()) {
            words.addAll(SettingsSearch.split(available.getDisplayLanguage(locale)));
            words.addAll(SettingsSearch.split(available.getDisplayCountry(locale)));
            words.addAll(SettingsSearch.split(available.getDisplayLanguage(available)));
        }

        for (AiProvider provider : AiProvider.values()) {
            words.addAll(SettingsSearch.split(provider.title));
        }

        sStrings = strings;
        sWords = words;
        sFormats = formats;
        sLocale = locale;
    }

    /**
     * A string of the app with arguments, e.g. "Speed: %s": matches "Speed: 1.25x", not "Speed: anything"
     */
    private static final class FormatString {
        private final Pattern mPattern;
        private final String mLongestPart;

        private FormatString(Pattern pattern, String longestPart) {
            mPattern = pattern;
            mLongestPart = longestPart;
        }

        @Nullable
        static FormatString from(String text) {
            Matcher matcher = FORMAT_ARG.matcher(text);
            StringBuilder regex = new StringBuilder("^");
            String longestPart = "";
            int start = 0;
            int args = 0;

            while (matcher.find()) {
                String part = text.substring(start, matcher.start()).replace("%%", "%");
                regex.append(Pattern.quote(part)).append("(.+?)");
                longestPart = part.length() > longestPart.length() ? part : longestPart;
                start = matcher.end();
                args++;
            }

            if (args == 0) {
                return null;
            }

            String part = text.substring(start).replace("%%", "%");
            regex.append(Pattern.quote(part)).append("$");
            longestPart = part.length() > longestPart.length() ? part : longestPart;

            // Nothing but arguments: anything would match
            if (longestPart.trim().length() < 2) {
                return null;
            }

            return new FormatString(Pattern.compile(regex.toString(), Pattern.DOTALL), longestPart);
        }

        boolean matches(String text) {
            if (!text.contains(mLongestPart)) {
                return false;
            }

            Matcher matcher = mPattern.matcher(text);

            if (!matcher.matches()) {
                return false;
            }

            for (int i = 1; i <= matcher.groupCount(); i++) {
                String arg = matcher.group(i).trim();

                if (!arg.isEmpty() && !NUMERIC.matcher(arg).matches() && !sStrings.contains(arg)) {
                    return false;
                }
            }

            return true;
        }
    }
}
