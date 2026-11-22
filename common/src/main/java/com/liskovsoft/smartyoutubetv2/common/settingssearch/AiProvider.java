package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import androidx.annotation.Nullable;

/**
 * Who answers the questions of the settings search, with the user's own API key (see AiSearchData)
 */
public enum AiProvider {
    CLAUDE("claude", "Claude", "https://platform.claude.com/settings/keys", "claude-opus-5-5", null),
    /**
     * The lite model: picking settings needs little, and it's busy less often than gemini-flash-latest (503 "high demand")
     */
    GEMINI("gemini", "Gemini", "https://aistudio.google.com/apikey", "gemini-flash-lite-latest",
            "gemini-flash-latest"),
    /**
     * Its models change often: picked from the ones of the key
     */
    OPENAI("openai", "OpenAI", "https://platform.openai.com/api-keys", null, null);

    /**
     * Saved: the same in every version
     */
    public final String id;
    public final String title;
    /**
     * Where a key is made, shown as a QR code
     */
    public final String keyPageUrl;
    @Nullable
    public final String defaultModel;
    /**
     * Asked once more when the model picked is busy (or the default model, when it's this one)
     */
    @Nullable
    public final String fallbackModel;

    AiProvider(String id, String title, String keyPageUrl, @Nullable String defaultModel, @Nullable String fallbackModel) {
        this.id = id;
        this.title = title;
        this.keyPageUrl = keyPageUrl;
        this.defaultModel = defaultModel;
        this.fallbackModel = fallbackModel;
    }

    @Nullable
    public static AiProvider fromId(@Nullable String id) {
        for (AiProvider provider : values()) {
            if (provider.id.equals(id)) {
                return provider;
            }
        }

        return null;
    }
}
