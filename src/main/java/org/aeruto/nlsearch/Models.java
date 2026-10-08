package org.aeruto.nlsearch;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.elasticsearch.common.settings.Settings;

import java.time.Duration;

/** The chat model built from the nlsearch.* settings. Rebuilt when they change. */
class Models {

    private volatile Settings settings;
    private volatile ChatModel model;   // answers with JSON only, for planning
    private volatile ChatModel prose;   // answers with sentences, for explaining

    Models(Settings settings) {
        this.settings = settings;
    }

    // wired to the cluster settings, see NLSearchPlugin.createComponents
    void reload(Settings changed) {
        settings = changed;
        model = null;
        prose = null;
    }

    /** "ollama/qwen2.5-coder:7b", shown in every response. */
    String name() {
        return NLSettings.PROVIDER.get(settings) + "/" + NLSettings.MODEL.get(settings);
    }

    /** The planner's model. Asked for one JSON object and nothing else. */
    ChatModel get() {
        ChatModel current = model;
        if (current == null) {
            // built on first use, so a bad key fails a request and not the node start
            current = build(settings, true);
            model = current;
        }
        return current;
    }

    /**
     * The same model without JSON mode, for turning a result back into a sentence.
     * With JSON forced on, an explanation comes back as {"count": 5} instead of English.
     */
    ChatModel prose() {
        ChatModel current = prose;
        if (current == null) {
            current = build(settings, false);
            prose = current;
        }
        return current;
    }

    static ChatModel build(Settings settings) {
        return build(settings, true);
    }

    static ChatModel build(Settings settings, boolean jsonOnly) {
        String provider = NLSettings.PROVIDER.get(settings);
        String modelName = NLSettings.MODEL.get(settings);
        String url = NLSettings.URL.get(settings);
        String apiKey = NLSettings.API_KEY.get(settings);
        Duration timeout = Duration.ofMillis(NLSettings.TIMEOUT.get(settings).millis());

        switch (provider) {
            case "ollama":
                return OllamaChatModel.builder()
                    .baseUrl(url.isEmpty() ? "http://localhost:11434" : url)
                    .modelName(modelName)
                    .temperature(0.0)
                    .responseFormat(jsonOnly ? ResponseFormat.JSON : ResponseFormat.TEXT)
                    // the rules, the mappings, the keyword values and ten turns of chat do not fit in
                    // ollama's default window, and it silently drops the oldest text when they overflow
                    .numCtx(16384)
                    .numPredict(2048)
                    .maxRetries(0) // langchain4j would otherwise retry 3 times, we retry at the plan level
                    .timeout(timeout)
                    .build();
            case "openai":
                // also groq, together, deepseek, openrouter, lm studio, vllm ... anything with the OpenAI api
                var openai = OpenAiChatModel.builder()
                    .baseUrl(url.isEmpty() ? "https://api.openai.com/v1" : url)
                    .apiKey(apiKey.isEmpty() ? "none" : apiKey) // local servers want some value
                    .modelName(modelName)
                    .maxRetries(0)
                    .timeout(timeout);
                if (modelName.matches("(o\\d|gpt-5).*") == false) {
                    openai.temperature(0.0); // the reasoning models refuse a temperature
                }
                return openai.build();
            case "anthropic": {
                var builder = AnthropicChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(0.0)
                    .maxTokens(2048)
                    .maxRetries(0)
                    .timeout(timeout);
                if (url.isEmpty() == false) {
                    builder.baseUrl(url);
                }
                return builder.build();
            }
            case "gemini": {
                var builder = GoogleAiGeminiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .temperature(0.0)
                    .maxOutputTokens(2048)
                    .maxRetries(0)
                    .timeout(timeout);
                if (url.isEmpty() == false) {
                    builder.baseUrl(url);
                }
                return builder.build();
            }
            default: // the setting validator already stops this
                throw new IllegalArgumentException("unknown provider [" + provider + "]");
        }
    }
}
