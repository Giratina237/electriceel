package com.liskovsoft.smartyoutubetv2.common.diagnostics;

import android.content.Context;
import android.os.Build;
import android.os.Build.VERSION;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.googlecommon.common.locale.LocaleManager;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.AppInfoHelpers;
import com.liskovsoft.sharedutils.okhttp.OkHttpManager;
import com.liskovsoft.smartyoutubetv2.common.BuildConfig;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.other.TunnelingFallback;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.FormatItem;
import com.liskovsoft.smartyoutubetv2.common.exoplayer.selector.track.SubtitleTrack;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.Skins;
import com.liskovsoft.smartyoutubetv2.common.prefs.DebuggingData;
import com.liskovsoft.smartyoutubetv2.common.prefs.GeneralData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.NetworkData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerData;
import com.liskovsoft.smartyoutubetv2.common.prefs.PlayerTweaksData;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What Settings → Debugging → Submit data sends (schema 1, see openapi.yaml of ElectricEelDataRestAPI): the app's settings, the
 * device and the recorded player failures. No account names, and of the proxy only whether it's on.
 */
public final class DiagnosticsReport {
    public static final int SCHEMA = 1;
    private static final Pattern BUILDER_NAME = Pattern.compile("\\beng\\.[^.\\s]+");
    /**
     * A video id where one may be: an id of the report's records, or the one after a lock wait's holder, e.g. "fetch 06cF1xPpRYY, 103 ms
     * in" or "next video's prefetch ..." (see FormatInfoWrapper#getLockHolder in MediaServiceCore), which may name a video none was
     * recorded for
     */
    private static final Pattern VIDEO_ID = Pattern.compile("(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{11}(?![A-Za-z0-9_-])");
    private static final Pattern LOCK_HOLDER_ID = Pattern.compile("\\b(fetch|prefetch) [A-Za-z0-9_-]{11}(?![A-Za-z0-9_-])");
    private static final String NO_VIDEO_ID = "<video>";

    private DiagnosticsReport() {
    }

    /**
     * Not on the main thread (the device's decoders are listed)
     *
     * @param failures as read from the log (see PlayerFailureLog#readAll)
     * @param videoStarts as read from theirs (see VideoStartLog#readAll)
     */
    public static JSONObject build(Context context, List<JSONObject> failures, List<JSONObject> videoStarts) {
        MediaServiceManager service = MediaServiceManager.instance();
        List<Account> accounts = service.getAccounts();

        return build(context, service.getSelectedAccount(), accounts != null ? accounts.size() : 0, failures, videoStarts,
                System.currentTimeMillis());
    }

    @VisibleForTesting
    static JSONObject build(Context context, @Nullable Account selectedAccount, int signedInAccounts, List<JSONObject> failures,
                            List<JSONObject> videoStarts, long nowMs) {
        Scrubber.update(context);
        DebuggingData debuggingData = DebuggingData.instance(context);
        JSONObject result = new JSONObject();

        try {
            result.put("schema", SCHEMA);
            result.put("submittedAt", formatUtc(nowMs));
            result.put("previousSubmission", buildPreviousSubmission(debuggingData));

            JSONObject app = new JSONObject();
            app.put("package", context.getPackageName());
            String versionName = AppInfoHelpers.getAppVersionName(context);
            // Required by the server
            app.put("versionName", versionName != null && !versionName.isEmpty() ? versionName : "unknown");
            app.put("versionCode", AppInfoHelpers.getAppVersionCode(context));
            app.put("flavor", BuildConfig.FLAVOR);
            app.put("buildType", BuildConfig.BUILD_TYPE);

            // A build of a checkout with changes has the version of the commit it's made from
            if (BuildConfig.GIT_COMMIT != null) {
                app.put("commit", BuildConfig.GIT_COMMIT);
                app.put("dirty", BuildConfig.GIT_DIRTY);
                app.put("build", BuildConfig.BUILD_ID);
                // Built with it from the checkouts next to it: their changes aren't the app's
                JSONObject modules = new JSONObject();
                putModule(modules, "MediaServiceCore", BuildConfig.MEDIA_SERVICE_CORE_COMMIT, BuildConfig.MEDIA_SERVICE_CORE_DIRTY);
                putModule(modules, "SharedModules", BuildConfig.SHARED_MODULES_COMMIT, BuildConfig.SHARED_MODULES_DIRTY);
                app.put("modules", modules);
            }

            app.put("settings", buildSettings(context));

            JSONObject accountsJson = new JSONObject();
            accountsJson.put("signedIn", signedInAccounts);
            accountsJson.put("active", debuggingData.getAccountNumber(selectedAccount));
            app.put("accounts", accountsJson);
            result.put("app", app);

            result.put("system", buildSystem());
            result.put("device", DeviceCapabilities.build(context));

            boolean includeVideoIds = debuggingData.isVideoIdsIncluded();

            if (!includeVideoIds) {
                removeVideoIds(failures, videoStarts);
            }

            JSONArray failuresJson = new JSONArray();

            for (JSONObject failure : failures) {
                failuresJson.put(failure);
            }

            result.put("playerFailures", failuresJson);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }

        try {
            // How the formats played, failures or not: the baseline the failures are compared with
            result.put("playbackStats", PlayerFailureLog.instance(context).readStats());
        } catch (JSONException | RuntimeException e) {
            // Sent without them
        }

        try {
            // Every video started, and where the time went (see VideoStarts). Without video ids, they were removed with the failures'.
            JSONArray videoStartsJson = new JSONArray();

            for (JSONObject start : videoStarts) {
                videoStartsJson.put(start);
            }

            result.put("videoStarts", videoStartsJson);
        } catch (JSONException | RuntimeException e) {
            // Sent without them
        }

        return result;
    }

    /**
     * The video ids left out (see DebuggingData#isVideoIdsIncluded): each record's own, and wherever a record's text names one, e.g. a
     * start's lock wait waiting for "fetch 06cF1xPpRYY" (SKCS2PB6 sent that with video ids off)
     */
    @VisibleForTesting
    static void removeVideoIds(List<JSONObject> failures, List<JSONObject> videoStarts) {
        Set<String> ids = new HashSet<>();

        for (List<JSONObject> records : Arrays.asList(failures, videoStarts)) {
            for (JSONObject record : records) {
                Object id = record.remove("videoId");

                if (id instanceof String) {
                    ids.add((String) id);
                }
            }
        }

        for (List<JSONObject> records : Arrays.asList(failures, videoStarts)) {
            for (JSONObject record : records) {
                removeVideoIds(record, ids);
            }
        }
    }

    private static void removeVideoIds(JSONObject json, Set<String> ids) {
        JSONArray names = json.names();

        for (int i = 0; names != null && i < names.length(); i++) {
            String name = names.optString(i);
            Object value = json.opt(name);
            Object scrubbed = removeVideoIdsOf(value, ids);

            if (scrubbed != value) {
                try {
                    json.put(name, scrubbed);
                } catch (JSONException e) {
                    // Not for a string
                }
            }
        }
    }

    /**
     * @return the value itself when it names no video
     */
    private static Object removeVideoIdsOf(Object value, Set<String> ids) {
        if (value instanceof JSONObject) {
            removeVideoIds((JSONObject) value, ids);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;

            for (int i = 0; i < array.length(); i++) {
                Object item = array.opt(i);
                Object scrubbed = removeVideoIdsOf(item, ids);

                if (scrubbed != item) {
                    try {
                        array.put(i, scrubbed);
                    } catch (JSONException e) {
                        // Not for a string
                    }
                }
            }
        } else if (value instanceof String) {
            String text = (String) value;
            String result = LOCK_HOLDER_ID.matcher(text).replaceAll("$1 " + NO_VIDEO_ID);
            Matcher matcher = VIDEO_ID.matcher(result);
            StringBuffer buffer = null;

            while (matcher.find()) {
                if (ids.contains(matcher.group())) {
                    if (buffer == null) {
                        buffer = new StringBuffer();
                    }

                    matcher.appendReplacement(buffer, NO_VIDEO_ID);
                }
            }

            if (buffer != null) {
                matcher.appendTail(buffer);
                result = buffer.toString();
            }

            return result.equals(text) ? value : result;
        }

        return value;
    }

    /**
     * The one sent before this from the app's data (see DataSubmitter#submit), what this one follows: an empty one after it had
     * nothing new, the first (null) had nothing yet. A backup restored brings the last of the install it was made on.
     */
    private static Object buildPreviousSubmission(DebuggingData debuggingData) throws JSONException {
        String id = debuggingData.getLastSubmissionId();

        if (id == null) {
            return JSONObject.NULL;
        }

        JSONObject previous = new JSONObject();
        previous.put("id", id);
        previous.put("submittedAt", formatUtc(debuggingData.getLastSubmissionTime()));

        return previous;
    }

    /**
     * Left out when it wasn't built with git
     */
    private static void putModule(JSONObject modules, String name, @Nullable String commit, boolean dirty) throws JSONException {
        if (commit == null) {
            return;
        }

        JSONObject module = new JSONObject();
        module.put("commit", commit);
        module.put("dirty", dirty);
        modules.put(name, module);
    }

    /**
     * The account in use's values, where they're per account
     */
    private static JSONObject buildSettings(Context context) throws JSONException {
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(context);
        MediaServiceData serviceData = MediaServiceData.instance();
        JSONObject settings = new JSONObject();

        JSONObject engine = new JSONObject();
        engine.put("selected", Utils.getNetworkEngineId(tweaksData.getPlayerDataSource()));
        engine.put("effective", Utils.getEffectiveNetworkEngine(context));
        settings.put("networkEngine", engine);

        JSONObject dns = new JSONObject();
        dns.put("preferredType", getDnsTypeName(tweaksData.getPreferredDnsType()));
        // Not the custom servers: a personal DNS-over-HTTPS URL names its owner
        dns.put("protocol", getDnsProtocolName(tweaksData.getPreferredDnsProtocol()));
        dns.put("fixEnabled", tweaksData.isDnsFixEnabled());
        settings.put("dns", dns);

        JSONObject network = new JSONObject();
        network.put("webProxyEnabled", GeneralData.instance(context).isProxyEnabled());
        network.put("conscryptEnabled", NetworkData.instance(context).isConscryptEnabled());
        network.put("ipv6TestEnabled", tweaksData.isIpv6TestEnabled());
        network.put("ipv6Disabled", OkHttpManager.isIpv6Disabled());
        settings.put("network", network);

        PlayerData playerData = PlayerData.instance(context);

        JSONObject video = new JSONObject();
        video.put("swDecoderForced", tweaksData.isSWDecoderForced());
        video.put("legacyCodecsForced", playerData.isLegacyCodecsForced());
        video.put("tunneledPlayback", tweaksData.isTunneledPlaybackEnabled());
        // On, but turned off for the run after it froze the picture or stalled, or for good after it did in two runs (see
        // TunnelingFallback)
        video.put("tunneledPlaybackOff", TunnelingFallback.isOff(context));
        // When the user last set it again while it was suspended: one more frozen run suspends it again (see TunnelingFallback#reset).
        // H5B64KBE to Y1D66CNW, the reset could only be told from tunneledPlaybackOff and the decoder's name.
        long tunnelingResetAt = TunnelingFallback.getSuspendedResetAt(context);
        video.put("tunneledPlaybackResetAt", tunnelingResetAt > 0 ? formatUtc(tunnelingResetAt) : JSONObject.NULL);
        video.put("avcOverVp9", tweaksData.isAvcOverVp9Preferred());
        video.put("amlogicFix", tweaksData.isAmlogicFixEnabled());
        video.put("sonyFrameDropFix", tweaksData.isSonyFrameDropFixEnabled());
        video.put("highBitrateFormats", tweaksData.isHighBitrateFormatsEnabled());
        video.put("altPresets", tweaksData.isAltPresetsEnabled());
        video.put("bufferType", getBufferTypeName(playerData.getVideoBufferType()));
        // On, a live stream plays from the end of its window, not 15 s behind it (see LiveEdge): the live starts of 2M1DY5CD and
        // MD8RQZM2, sent to the end again and again, were taken for ones placed 15 s behind it
        video.put("bufferOnStreamsDisabled", tweaksData.isBufferOnStreamsDisabled());
        video.put("networkErrorFixingDisabled", tweaksData.isNetworkErrorFixingDisabled());
        // The format asked for, as a failure's formats say it (see FormatAttempts#toJson), and whether it's the device's default rather
        // than the user's pick: 1080p on a 720p screen over a slow link (NVHZS7YS) and AV1 on a software decoder (XF9KYTK1) said neither
        FormatItem videoFormat = playerData.getFormat(FormatItem.TYPE_VIDEO);
        video.put("requestedFormat", describeRequested(PlayerFailure.describeVideo(videoFormat)));
        video.put("requestedFormatDefault", videoFormat != null && videoFormat.equals(playerData.getDefaultVideoFormat()));
        settings.put("video", video);

        JSONObject audio = new JSONObject();
        audio.put("delayEnabled", playerData.isAudioDelayEnabled());
        audio.put("delayMs", playerData.getAudioDelayMs());
        audio.put("timeStretching", tweaksData.isAudioTimeStretchingEnabled());
        audio.put("syncFix", tweaksData.isAudioSyncFixEnabled());
        audio.put("unsafeFormats", tweaksData.isUnsafeAudioFormatsEnabled());
        audio.put("volume", playerData.getPlayerVolume());
        audio.put("autoVolume", tweaksData.isPlayerAutoVolumeEnabled());
        audio.put("audioFocus", tweaksData.isAudioFocusEnabled());
        audio.put("language", nullable(emptyToNull(playerData.getAudioLanguage())));
        // As the video's: off with the sound off
        FormatItem audioFormat = playerData.getFormat(FormatItem.TYPE_AUDIO);
        audio.put("requestedFormat", playerData.isSoundOff() ? "off" : describeRequested(PlayerFailure.describeAudio(audioFormat)));
        audio.put("requestedFormatDefault", audioFormat != null && audioFormat.equals(playerData.getDefaultAudioFormat()));
        settings.put("audio", audio);

        JSONObject autoFrameRate = new JSONObject();
        autoFrameRate.put("enabled", playerData.isAfrEnabled());
        autoFrameRate.put("fpsCorrection", playerData.isAfrFpsCorrectionEnabled());
        autoFrameRate.put("resolutionSwitch", playerData.isAfrResSwitchEnabled());
        autoFrameRate.put("pauseMs", playerData.getAfrPauseMs());
        settings.put("autoFrameRate", autoFrameRate);

        settings.put("subtitles", buildSubtitleSettings(playerData));
        settings.put("playbackMode", getPlaybackModeName(playerData.getPlaybackMode()));
        // The player has an active media session (off with Disable playback notifications): what tells it to pause from outside
        // the app, e.g. HDMI-CEC or a Bluetooth device (QYZPW0FN: six pauses came through it)
        settings.put("mediaSession", !tweaksData.isPlaybackNotificationsDisabled());
        settings.put("youtubeClientsOrder", new JSONArray(serviceData.getClientOrder()));
        settings.put("currentClient", YouTubeServiceManager.getCurrentClientName());

        // The skin and its card previews: a preview opens the focused card's video, its fetch holds the fetches' lock like a video
        // opened (CGHFJ3BZ: a start waited behind the fetch of a video no start was recorded for)
        MainUIData mainUIData = MainUIData.instance(context);
        JSONObject ui = new JSONObject();
        ui.put("skin", getSkinName(Skins.getAppliedSkinId()));
        ui.put("cardPreview", getCardPreviewName(mainUIData.getCardPreviewType()));
        settings.put("ui", ui);

        // The ones YouTube is asked for: chosen in the app, else the system's
        LocaleManager localeManager = LocaleManager.instance();
        settings.put("language", nullable(emptyToNull(localeManager.getLanguage())));
        settings.put("country", nullable(emptyToNull(localeManager.getCountry())));

        return settings;
    }

    /**
     * A format asked for, auto when none is set (see FormatAttempts#toJson)
     */
    private static String describeRequested(@Nullable String description) {
        return description != null ? description : "auto";
    }

    /**
     * Whether the videos play with subtitles, and auto-translated ones (their requests are the ones YouTube answers 429 too many
     * requests the most): a 429 of a track load can be told apart from the media's
     */
    @VisibleForTesting
    static JSONObject buildSubtitleSettings(PlayerData playerData) throws JSONException {
        JSONObject subtitles = new JSONObject();
        boolean isPerChannel = playerData.isSubtitlesPerChannelEnabled();
        // Per channel: the channels it's on for play the last one chosen
        FormatItem format = isPerChannel ? playerData.getLastSubtitleFormat() : playerData.getFormat(FormatItem.TYPE_SUBTITLE);
        boolean isEnabled = format != null && !FormatItem.SUBTITLE_NONE.equals(format);

        subtitles.put("enabled", isEnabled);
        subtitles.put("perChannel", isPerChannel);
        subtitles.put("autoTranslated", isEnabled && SubtitleTrack.isAuto(format.getLanguage()));

        return subtitles;
    }

    private static JSONObject buildSystem() throws JSONException {
        JSONObject system = new JSONObject();

        system.put("androidVersion", VERSION.RELEASE);
        system.put("sdkInt", VERSION.SDK_INT);
        system.put("make", Build.MANUFACTURER);
        system.put("brand", Build.BRAND);
        system.put("model", Build.MODEL);
        system.put("modelNumber", Build.DEVICE);
        system.put("product", Build.PRODUCT);
        system.put("hardware", Build.HARDWARE);
        system.put("sku", VERSION.SDK_INT >= 31 ? nullable(Build.SKU) : JSONObject.NULL);
        // The firmware: e.g. which Shield Experience a device bug is in, and whether an update fixed it
        system.put("buildId", nullable(scrubBuilder(Build.ID)));
        system.put("buildDisplay", nullable(scrubBuilder(Build.DISPLAY)));
        system.put("buildIncremental", nullable(scrubBuilder(VERSION.INCREMENTAL)));
        system.put("securityPatch", nullable(VERSION.SECURITY_PATCH));

        return system;
    }

    /**
     * The settings of the playback in use, for a failure: the ones a failure depends on most (see PlayerFailure#create)
     */
    static JSONObject buildPlaybackSettings(Context context) throws JSONException {
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(context);
        PlayerData playerData = PlayerData.instance(context);
        JSONObject result = new JSONObject();

        result.put("dns", getDnsTypeName(tweaksData.getPreferredDnsType()));
        result.put("dnsProtocol", getDnsProtocolName(tweaksData.getPreferredDnsProtocol()));
        result.put("ipv6Disabled", OkHttpManager.isIpv6Disabled());
        result.put("tunneledPlayback", tweaksData.isTunneledPlaybackEnabled());
        result.put("tunneledPlaybackOff", TunnelingFallback.isOff(context));
        result.put("swDecoderForced", tweaksData.isSWDecoderForced());
        result.put("legacyCodecsForced", playerData.isLegacyCodecsForced());
        result.put("avcOverVp9", tweaksData.isAvcOverVp9Preferred());
        result.put("highBitrateFormats", tweaksData.isHighBitrateFormatsEnabled());
        result.put("bufferType", getBufferTypeName(playerData.getVideoBufferType()));
        result.put("autoFrameRate", playerData.isAfrEnabled());
        result.put("audioDelayMs", playerData.isAudioDelayEnabled() ? playerData.getAudioDelayMs() : 0);
        result.put("audioTimeStretching", tweaksData.isAudioTimeStretchingEnabled());
        result.put("audioSyncFix", tweaksData.isAudioSyncFixEnabled());
        result.put("playbackMode", getPlaybackModeName(playerData.getPlaybackMode()));

        return result;
    }

    /**
     * What's done at the end of a video (Playback mode): pause and list leave the player at its end
     */
    private static String getPlaybackModeName(int playbackMode) {
        switch (playbackMode) {
            case PlayerConstants.PLAYBACK_MODE_ALL:
                return "all";
            case PlayerConstants.PLAYBACK_MODE_ONE:
                return "one";
            case PlayerConstants.PLAYBACK_MODE_SHUFFLE:
                return "shuffle";
            case PlayerConstants.PLAYBACK_MODE_LIST:
                return "list";
            case PlayerConstants.PLAYBACK_MODE_REVERSE_LIST:
                return "reverse_list";
            case PlayerConstants.PLAYBACK_MODE_PAUSE:
                return "pause";
            case PlayerConstants.PLAYBACK_MODE_CLOSE:
                return "close";
            default:
                return "unknown";
        }
    }

    private static String getBufferTypeName(int bufferType) {
        switch (bufferType) {
            case PlayerConstants.BUFFER_LOW:
                return "low";
            case PlayerConstants.BUFFER_HIGH:
                return "high";
            case PlayerConstants.BUFFER_HIGHEST:
                return "highest";
            default:
                return "medium";
        }
    }

    private static String getSkinName(int skinId) {
        switch (skinId) {
            case MainUIData.SKIN_MODERN:
                return "modern";
            case MainUIData.SKIN_HYBRID:
                return "hybrid";
            default:
                return "classic";
        }
    }

    private static String getCardPreviewName(int cardPreviewType) {
        switch (cardPreviewType) {
            case MainUIData.CARD_PREVIEW_MUTED:
                return "muted";
            case MainUIData.CARD_PREVIEW_FULL:
                return "full";
            default:
                return "off";
        }
    }

    private static String getDnsTypeName(int dnsType) {
        switch (dnsType) {
            case PlayerTweaksData.DNS_TYPE_IPV4:
                return "ipv4";
            case PlayerTweaksData.DNS_TYPE_GOOGLE:
                return "google";
            case PlayerTweaksData.DNS_TYPE_CLOUDFLARE:
                return "cloudflare";
            case PlayerTweaksData.DNS_TYPE_QUAD9:
                return "quad9";
            case PlayerTweaksData.DNS_TYPE_ADGUARD:
                return "adguard";
            case PlayerTweaksData.DNS_TYPE_CUSTOM:
                return "custom";
            default:
                return "system";
        }
    }

    /**
     * How the public DNS servers are asked: not the system's DNS's (e.g. Android's Private DNS)
     */
    private static String getDnsProtocolName(int protocol) {
        return protocol == PlayerTweaksData.DNS_PROTOCOL_DOH ? "doh" : "plain";
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Object nullable(@Nullable Object value) {
        return value != null ? value : JSONObject.NULL;
    }

    /**
     * A ROM built by hand has who built it in its build number, e.g. "eng.alice.20211010.123456" (an eng or userdebug build's): maybe
     * the user. Taken out.
     */
    @Nullable
    private static String scrubBuilder(@Nullable String text) {
        return text != null ? BUILDER_NAME.matcher(text).replaceAll("eng.<user>") : null;
    }

    private static String formatUtc(long ms) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));

        return format.format(new Date(ms));
    }
}
