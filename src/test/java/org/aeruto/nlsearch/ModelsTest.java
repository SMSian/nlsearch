package org.aeruto.nlsearch;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.elasticsearch.common.settings.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelsTest {

    static Settings settings(String... keyValues) {
        Settings.Builder builder = Settings.builder();
        for (int i = 0; i < keyValues.length; i += 2) {
            builder.put(keyValues[i], keyValues[i + 1]);
        }
        return builder.build();
    }

    @Test
    void ollamaOnLocalhostIsTheDefault() {
        Models models = new Models(Settings.EMPTY);
        assertEquals("ollama/qwen2.5-coder:7b", models.name());
        assertInstanceOf(OllamaChatModel.class, models.get());
    }

    @Test
    void everyProviderBuilds() {
        assertInstanceOf(OllamaChatModel.class, Models.build(settings("nlsearch.provider", "ollama", "nlsearch.model", "llama3.2:3b")));
        assertInstanceOf(OpenAiChatModel.class, Models.build(settings("nlsearch.provider", "openai", "nlsearch.model", "gpt-4o-mini", "nlsearch.api_key", "sk-test")));
        assertInstanceOf(OpenAiChatModel.class, Models.build(settings("nlsearch.provider", "openai", "nlsearch.model", "llama3", "nlsearch.url", "http://localhost:11434/v1")));
        assertInstanceOf(AnthropicChatModel.class, Models.build(settings("nlsearch.provider", "anthropic", "nlsearch.model", "claude-sonnet-4-5", "nlsearch.api_key", "sk-ant-test")));
        assertInstanceOf(GoogleAiGeminiChatModel.class, Models.build(settings("nlsearch.provider", "gemini", "nlsearch.model", "gemini-2.5-flash", "nlsearch.api_key", "AIza-test")));
    }

    @Test
    void anUnknownProviderIsRejectedWhenTheSettingIsRead() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> NLSettings.PROVIDER.get(settings("nlsearch.provider", "skynet")));
        assertTrue(e.getMessage().contains("skynet"), e.getMessage());
        assertTrue(e.getMessage().contains("ollama"), e.getMessage());
    }

    @Test
    void theModelIsBuiltOnceAndReused() {
        Models models = new Models(Settings.EMPTY);
        assertSame(models.get(), models.get());
    }

    @Test
    void reloadSwitchesToTheNewSettings() {
        Models models = new Models(Settings.EMPTY);
        ChatModel before = models.get();
        models.reload(settings("nlsearch.model", "llama3.2:3b"));
        assertEquals("ollama/llama3.2:3b", models.name());
        assertNotSame(before, models.get());
    }

    @Test
    void timeoutIsATimeValue() {
        assertEquals(60_000, NLSettings.TIMEOUT.get(Settings.EMPTY).millis());
        assertEquals(5_000, NLSettings.TIMEOUT.get(settings("nlsearch.timeout", "5s")).millis());
    }
}
