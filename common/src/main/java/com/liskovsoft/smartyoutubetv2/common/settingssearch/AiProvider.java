package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import androidx.annotation.Nullable;

/**
 * Who answers the questions of the settings search, with the user's own API key (see AiSearchData)
 */
public enum AiProvider {
    CLAUDE("claude", "Claude", "https://platform.claude.com/settings/keys", "claude-opus-5-5"),
    GEMINI("gemini", "Gemini", "https://aistudio.google.com/apikey", "gemini-flash-latest"),
    /**
     * Its models change often: picked from the ones of the key
     */
    OPENAI("openai", "OpenAI", "https://platform.openai.com/api-keys", null);

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

    AiProvider(String id, String title, String keyPageUrl, @Nullable String defaultModel) {
        this.id = id;
        this.title = title;
        this.keyPageUrl = keyPageUrl;
        this.defaultModel = defaultModel;
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
