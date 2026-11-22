package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class AiClientTest {
    @Test
    public void claude_onlyTheText() throws Exception {
        String response = "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"{x}\"}," +
                "{\"type\":\"text\",\"text\":\"{\\\"reply\\\":\\\"Open Subtitles\\\",\\\"settings\\\":[\\\"s3\\\",\\\"s3\\\",\\\"s9\\\"]}\"}]}";

        AiClient.Answer answer = AiClient.parseClaude(response);

        assertEquals("Open Subtitles", answer.reply);
        assertEquals(Arrays.asList("s3", "s9"), answer.settings);
    }

    @Test
    public void claude_refusal() {
        assertKind(AiClient.ErrorKind.DECLINED, () -> AiClient.parseClaude("{\"stop_reason\":\"refusal\",\"content\":[]}"));
    }

    @Test
    public void claude_body() throws Exception {
        JSONObject body = AiClient.getClaudeBody("claude-opus-5-5", "sys", "q", true, true);

        assertEquals("ephemeral", body.getJSONArray("system").getJSONObject(0).getJSONObject("cache_control").getString("type"));
        assertEquals("default", body.getString("fallbacks"));
        assertEquals("json_schema", body.getJSONObject("output_config").getJSONObject("format").getString("type"));
        assertFalse(body.has("temperature"));

        JSONObject minimal = AiClient.getClaudeBody("claude-haiku-5-5", "sys", "q", false, false);
        assertFalse(minimal.has("output_config"));
        assertFalse(minimal.has("fallbacks"));
    }

    @Test
    public void chat_answer() throws Exception {
        String response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":" +
                "\"{\\\"reply\\\":\\\"Hi\\\",\\\"settings\\\":[]}\",\"refusal\":null}}]}";

        AiClient.Answer answer = AiClient.parseChat(response);

        assertEquals("Hi", answer.reply);
        assertTrue(answer.settings.isEmpty());
    }

    @Test
    public void chat_refusal() {
        assertKind(AiClient.ErrorKind.DECLINED, () -> AiClient.parseChat(
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null,\"refusal\":\"No\"}}]}"));
    }

    @Test
    public void answer_withinText() throws Exception {
        AiClient.Answer answer = AiClient.parseAnswer("Sure:\n```json\n{\"reply\":\"x\",\"settings\":[\"s1\"]}\n```");

        assertEquals(Arrays.asList("s1"), answer.settings);
        assertKind(AiClient.ErrorKind.BAD_ANSWER, () -> AiClient.parseAnswer("no json"));
    }

    @Test
    public void geminiModels_withoutPrefix_onlyChat() throws Exception {
        List<AiClient.Model> models = AiClient.parseChatModels(AiProvider.GEMINI, "{\"data\":[{\"id\":\"models/gemini-flash-latest\"}," +
                "{\"id\":\"models/text-embedding-004\"},{\"id\":\"models/gemini-2.5-flash-preview-tts\"}]}");

        assertEquals(1, models.size());
        assertEquals("gemini-flash-latest", models.get(0).id);
    }

    @Test
    public void openAiModels_onlyChat() throws Exception {
        List<AiClient.Model> models = AiClient.parseChatModels(AiProvider.OPENAI, "{\"data\":[{\"id\":\"gpt-5.5\"},{\"id\":\"o4-mini\"}," +
                "{\"id\":\"whisper-1\"},{\"id\":\"gpt-image-1\"},{\"id\":\"text-embedding-3-small\"}]}");

        assertEquals(2, models.size());
    }

    private interface Parse {
        void run() throws Exception;
    }

    private static void assertKind(AiClient.ErrorKind kind, Parse parse) {
        try {
            parse.run();
            fail("No error");
        } catch (AiClient.AiException e) {
            assertEquals(kind, e.kind);
        } catch (Exception e) {
            fail(e.toString());
        }
    }
}
