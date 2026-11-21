package com.liskovsoft.smartyoutubetv2.common.settingssearch;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.liskovsoft.sharedutils.okhttp.OkHttpManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Asks the AI of the settings search, with the user's key: one request per question. Over plain HTTP (OkHttp, as the rest of the
 * app): the SDKs of the providers would add Kotlin or Jackson to the app for three requests.<br/>
 * Claude: the Messages API, the settings cached (they're the same for each question). Gemini: its OpenAI compatible API. OpenAI:
 * Chat Completions. Each answers JSON of a schema: {"reply": "...", "settings": ["s12", ...]}.
 */
public final class AiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String CLAUDE_URL = "https://api.anthropic.com/v1/";
    private static final String CLAUDE_VERSION = "2026-10-21";
    private static final String CLAUDE_FALLBACKS_BETA = "server-side-fallback-2026-07-01";
    /**
     * When one declines a question, another model answers it (see the fallbacks of the Messages API). Not Haiku.
     */
    private static final Set<String> CLAUDE_FALLBACK_MODELS = new HashSet<>(Arrays.asList(
            "claude-fable-5-2", "claude-fable-5-1", "claude-fable-5", "claude-opus-5-5", "claude-opus-5", "claude-sonnet-5-5"));
    private static final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/";
    private static final String OPENAI_URL = "https://api.openai.com/v1/";
    private static final int MAX_TOKENS = 16000;
    private static OkHttpClient sClient;

    /**
     * Why there's no answer
     */
    public enum ErrorKind {
        /** The key is wrong, or can't use the model */
        KEY_REFUSED,
        /** Too many questions, or no credit left */
        LIMIT,
        /** No connection, or too slow */
        NETWORK,
        /** The model declined to answer */
        DECLINED,
        /** An answer that isn't of the schema */
        BAD_ANSWER,
        /** No such model */
        MODEL,
        /** The provider's servers failed */
        SERVER
    }

    public static class AiException extends Exception {
        public final ErrorKind kind;

        AiException(ErrorKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        AiException(ErrorKind kind, Throwable cause) {
            super(cause);
            this.kind = kind;
        }
    }

    public static final class Answer {
        public final String reply;
        /**
         * The ids of the settings, as answered (maybe some that aren't in the catalog)
         */
        public final List<String> settings;

        Answer(String reply, List<String> settings) {
            this.reply = reply;
            this.settings = Collections.unmodifiableList(settings);
        }
    }

    public static final class Model {
        public final String id;
        public final String title;

        Model(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    private AiClient() {
    }

    /**
     * On the main thread when it's done. Disposing it cancels the request.
     */
    public static Observable<Answer> ask(AiProvider provider, String model, String key, String system, String user) {
        return run(call -> {
            if (provider == AiProvider.CLAUDE) {
                return parseClaude(postClaude(call, model, key, system, user));
            }

            return parseChat(postChat(call, provider, model, key, system, user));
        });
    }

    /**
     * The models the key can use: the key works when there are any
     */
    public static Observable<List<Model>> listModels(AiProvider provider, String key) {
        return run(call -> {
            if (provider == AiProvider.CLAUDE) {
                return listClaudeModels(call, key);
            }

            Request request = new Request.Builder()
                    .url(getChatUrl(provider) + "models")
                    .header("Authorization", "Bearer " + key)
                    .build();

            return parseChatModels(provider, execute(call, request));
        });
    }

    private interface Job<T> {
        T run(AtomicReference<Call> call) throws AiException;
    }

    private static <T> Observable<T> run(Job<T> job) {
        return Observable.<T>create(emitter -> {
            AtomicReference<Call> call = new AtomicReference<>();
            emitter.setCancellable(() -> {
                Call current = call.get();

                if (current != null) {
                    current.cancel();
                }
            });

            try {
                T result = job.run(call);

                if (!emitter.isDisposed()) {
                    emitter.onNext(result);
                    emitter.onComplete();
                }
            } catch (AiException e) {
                if (!emitter.isDisposed()) {
                    emitter.onError(e);
                }
            }
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread());
    }

    // Claude

    private static String postClaude(AtomicReference<Call> call, String model, String key, String system, String user) throws AiException {
        boolean fallbacks = CLAUDE_FALLBACK_MODELS.contains(model);

        try {
            return execute(call, claudeRequest(model, key, getClaudeBody(model, system, user, true, fallbacks), fallbacks));
        } catch (HttpError e) {
            if (e.code != 400) {
                throw e.toAiException();
            }
        }

        // E.g. a model that has no effort or structured outputs: asked as plainly as can be, the JSON asked in the instructions
        try {
            return execute(call, claudeRequest(model, key, getClaudeBody(model, system, user, false, false), false));
        } catch (HttpError e) {
            throw e.toAiException();
        }
    }

    private static Request claudeRequest(String model, String key, JSONObject body, boolean fallbacks) {
        Request.Builder builder = new Request.Builder()
                .url(CLAUDE_URL + "messages")
                .header("x-api-key", key)
                .header("anthropic-version", CLAUDE_VERSION)
                .post(RequestBody.create(body.toString(), JSON));

        if (fallbacks) {
            builder.header("anthropic-beta", CLAUDE_FALLBACKS_BETA);
        }

        return builder.build();
    }

    @VisibleForTesting
    static JSONObject getClaudeBody(String model, String system, String user, boolean full, boolean fallbacks) {
        try {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("max_tokens", MAX_TOKENS)
                    .put("system", new JSONArray().put(new JSONObject()
                            .put("type", "text")
                            .put("text", system)
                            // The same for each question
                            .put("cache_control", new JSONObject().put("type", "ephemeral"))))
                    .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)));

            if (full) {
                body.put("output_config", new JSONObject()
                        .put("effort", "low")
                        .put("format", new JSONObject().put("type", "json_schema").put("schema", getSchema())));
            }

            if (fallbacks) {
                body.put("fallbacks", "default");
            }

            return body;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @VisibleForTesting
    static Answer parseClaude(String response) throws AiException {
        try {
            JSONObject json = new JSONObject(response);
            String stopReason = json.optString("stop_reason");

            if ("refusal".equals(stopReason)) {
                throw new AiException(ErrorKind.DECLINED, "Declined");
            }

            if ("max_tokens".equals(stopReason)) {
                throw new AiException(ErrorKind.BAD_ANSWER, "Cut off");
            }

            JSONArray content = json.optJSONArray("content");
            StringBuilder text = new StringBuilder();

            // Only the text: not the thinking, nor a note that another model answered
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject block = content.optJSONObject(i);

                if (block != null && "text".equals(block.optString("type"))) {
                    text.append(block.optString("text"));
                }
            }

            return parseAnswer(text.toString());
        } catch (JSONException e) {
            throw new AiException(ErrorKind.BAD_ANSWER, e);
        }
    }

    private static List<Model> listClaudeModels(AtomicReference<Call> call, String key) throws AiException {
        List<Model> models = new ArrayList<>();
        String after = null;

        for (int page = 0; page < 5; page++) {
            Request request = new Request.Builder()
                    .url(CLAUDE_URL + "models?limit=100" + (after != null ? "&after_id=" + after : ""))
                    .header("x-api-key", key)
                    .header("anthropic-version", CLAUDE_VERSION)
                    .build();

            try {
                JSONObject json = new JSONObject(execute(call, request));
                JSONArray data = json.optJSONArray("data");

                for (int i = 0; data != null && i < data.length(); i++) {
                    JSONObject item = data.getJSONObject(i);
                    String id = item.getString("id");
                    models.add(new Model(id, item.optString("display_name", id)));
                }

                if (!json.optBoolean("has_more") || json.isNull("last_id")) {
                    break;
                }

                after = json.getString("last_id");
            } catch (JSONException e) {
                throw new AiException(ErrorKind.BAD_ANSWER, e);
            }
        }

        return models;
    }

    // Gemini and OpenAI

    private static String getChatUrl(AiProvider provider) {
        return provider == AiProvider.GEMINI ? GEMINI_URL : OPENAI_URL;
    }

    private static String postChat(AtomicReference<Call> call, AiProvider provider, String model, String key, String system, String user)
            throws AiException {
        try {
            return execute(call, chatRequest(provider, key, getChatBody(model, system, user, true)));
        } catch (HttpError e) {
            if (e.code != 400) {
                throw e.toAiException();
            }
        }

        // E.g. a model that doesn't reason, or has no structured outputs
        try {
            return execute(call, chatRequest(provider, key, getChatBody(model, system, user, false)));
        } catch (HttpError e) {
            throw e.toAiException();
        }
    }

    private static Request chatRequest(AiProvider provider, String key, JSONObject body) {
        return new Request.Builder()
                .url(getChatUrl(provider) + "chat/completions")
                .header("Authorization", "Bearer " + key)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }

    @VisibleForTesting
    static JSONObject getChatBody(String model, String system, String user, boolean full) {
        try {
            JSONObject body = new JSONObject()
                    .put("model", model)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", system))
                            .put(new JSONObject().put("role", "user").put("content", user)));

            if (full) {
                body.put("reasoning_effort", "low");
                body.put("response_format", new JSONObject()
                        .put("type", "json_schema")
                        .put("json_schema", new JSONObject()
                                .put("name", "settings_answer")
                                .put("strict", true)
                                .put("schema", getSchema())));
            }

            return body;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @VisibleForTesting
    static Answer parseChat(String response) throws AiException {
        try {
            JSONArray choices = new JSONObject(response).optJSONArray("choices");

            if (choices == null || choices.length() == 0) {
                throw new AiException(ErrorKind.BAD_ANSWER, "No choices");
            }

            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.optJSONObject("message");

            if (message != null && !message.isNull("refusal") && !message.optString("refusal").isEmpty()) {
                throw new AiException(ErrorKind.DECLINED, "Declined");
            }

            String reason = choice.optString("finish_reason");

            if ("content_filter".equals(reason)) {
                throw new AiException(ErrorKind.DECLINED, "Filtered");
            }

            if ("length".equals(reason)) {
                throw new AiException(ErrorKind.BAD_ANSWER, "Cut off");
            }

            return parseAnswer(message != null ? message.optString("content") : "");
        } catch (JSONException e) {
            throw new AiException(ErrorKind.BAD_ANSWER, e);
        }
    }

    @VisibleForTesting
    static List<Model> parseChatModels(AiProvider provider, String response) throws AiException {
        List<Model> models = new ArrayList<>();

        try {
            JSONArray data = new JSONObject(response).optJSONArray("data");

            for (int i = 0; data != null && i < data.length(); i++) {
                String id = data.getJSONObject(i).getString("id");

                // Gemini: "models/gemini-..."
                if (id.startsWith("models/")) {
                    id = id.substring("models/".length());
                }

                if (isChatModel(provider, id)) {
                    models.add(new Model(id, id));
                }
            }
        } catch (JSONException e) {
            throw new AiException(ErrorKind.BAD_ANSWER, e);
        }

        Collections.sort(models, (model1, model2) -> model1.id.compareTo(model2.id));

        return models;
    }

    /**
     * Of the models of the key, the ones that chat (not the ones that make pictures, speech, embeddings...)
     */
    private static boolean isChatModel(AiProvider provider, String id) {
        String lower = id.toLowerCase(Locale.ROOT);

        for (String other : new String[] {"embed", "image", "tts", "audio", "realtime", "transcribe", "speech", "live", "search",
                "moderation", "dall-e", "whisper", "instruct", "aqa", "veo", "imagen", "computer-use", "codex", "sora"}) {
            if (lower.contains(other)) {
                return false;
            }
        }

        if (provider == AiProvider.GEMINI) {
            return lower.startsWith("gemini") || lower.startsWith("gemma");
        }

        return lower.startsWith("gpt-") || lower.startsWith("chatgpt") || lower.matches("o\\d.*");
    }

    // Both

    private static JSONObject getSchema() throws JSONException {
        return new JSONObject()
                .put("type", "object")
                .put("properties", new JSONObject()
                        .put("reply", new JSONObject().put("type", "string"))
                        .put("settings", new JSONObject().put("type", "array").put("items", new JSONObject().put("type", "string"))))
                .put("required", new JSONArray().put("reply").put("settings"))
                .put("additionalProperties", false);
    }

    /**
     * The JSON of the answer, also within other text (a model asked without the schema might add some)
     */
    @VisibleForTesting
    static Answer parseAnswer(@Nullable String text) throws AiException {
        if (text == null) {
            throw new AiException(ErrorKind.BAD_ANSWER, "Empty");
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');

        if (start < 0 || end <= start) {
            throw new AiException(ErrorKind.BAD_ANSWER, "No JSON");
        }

        try {
            JSONObject json = new JSONObject(text.substring(start, end + 1));
            List<String> settings = new ArrayList<>();
            JSONArray array = json.optJSONArray("settings");

            for (int i = 0; array != null && i < array.length(); i++) {
                String id = array.optString(i, "").trim();

                if (!id.isEmpty() && !settings.contains(id)) {
                    settings.add(id);
                }
            }

            return new Answer(json.optString("reply", "").trim(), settings);
        } catch (JSONException e) {
            throw new AiException(ErrorKind.BAD_ANSWER, e);
        }
    }

    private static synchronized OkHttpClient getClient() {
        if (sClient == null) {
            OkHttpClient.Builder builder = OkHttpManager.instance().getClient().newBuilder();
            // The app's logger would log the key and the answers
            builder.interceptors().clear();
            builder.networkInterceptors().clear();
            sClient = builder
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(90, TimeUnit.SECONDS)
                    .build();
        }

        return sClient;
    }

    private static String execute(AtomicReference<Call> holder, Request request) throws HttpError, AiException {
        Call call = getClient().newCall(request);
        holder.set(call);

        try (Response response = call.execute()) {
            ResponseBody body = response.body();
            String text = body != null ? body.string() : "";

            if (!response.isSuccessful()) {
                throw new HttpError(response.code(), text);
            }

            return text;
        } catch (IOException e) {
            throw new AiException(ErrorKind.NETWORK, e);
        }
    }

    private static final class HttpError extends AiException {
        private final int code;

        HttpError(int code, String body) {
            super(getKind(code, body), "HTTP " + code + ": " + getMessage(body));
            this.code = code;
        }

        AiException toAiException() {
            return new AiException(kind, getMessage());
        }

        private static ErrorKind getKind(int code, String body) {
            if (code == 401 || code == 403) {
                return ErrorKind.KEY_REFUSED;
            }

            if (code == 402 || code == 429 || code == 529) {
                return ErrorKind.LIMIT;
            }

            // E.g. 503 "This model is currently experiencing high demand": busy, not a wrong model
            if (code >= 500) {
                return ErrorKind.SERVER;
            }

            if (code == 404 || code == 400 && getMessage(body).toLowerCase(Locale.ROOT).contains("model")) {
                return ErrorKind.MODEL;
            }

            return ErrorKind.BAD_ANSWER;
        }

        /**
         * The error's message, of either API: {"error": {"message": "..."}} (Gemini may answer a list of them)
         */
        private static String getMessage(String body) {
            try {
                Object json = body.trim().startsWith("[") ? new JSONArray(body).opt(0) : new JSONObject(body);

                if (json instanceof JSONObject) {
                    JSONObject error = ((JSONObject) json).optJSONObject("error");

                    if (error != null) {
                        return error.optString("message", body);
                    }
                }
            } catch (JSONException e) {
                // Not JSON
            }

            return body.length() > 200 ? body.substring(0, 200) : body;
        }
    }
}
