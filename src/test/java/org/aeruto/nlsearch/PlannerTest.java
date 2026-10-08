package org.aeruto.nlsearch;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerTest extends EsLoggingTest {

    /** Answers with a fixed string and remembers what it was asked. */
    static class FakeModel implements ChatModel {
        final String answer;
        ChatRequest seen;

        FakeModel(String answer) {
            this.answer = answer;
        }

        @Override
        public ChatResponse doChat(ChatRequest request) {
            seen = request;
            return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
        }
    }

    static Models using(ChatModel model) {
        return new Models(Settings.EMPTY) {
            @Override
            ChatModel get() {
                return model;
            }
        };
    }

    @Test
    void sendsTheRulesThenTheHistoryThenTheRequest() {
        FakeModel model = new FakeModel("{\"action\": \"search\", \"index\": \"products\", \"body\": {\"query\": {\"match\": {\"name\": \"red\"}}}}");
        Planner planner = new Planner(using(model));
        List<History.Turn> history = List.of(new History.Turn("show products", "{\"action\":\"search\",\"index\":\"products\"}", "3 hits"));
        Map<String, Object> mappings = Map.of("products", Map.of("properties", Map.of("name", Map.of("type", "text"))));

        Plan plan = planner.plan("now only red ones", history, mappings, Map.of(), Map.of());

        assertEquals("search", plan.action());
        assertEquals("products", plan.index());

        List<ChatMessage> messages = model.seen.messages();
        assertEquals(4, messages.size());
        assertEquals(Planner.INSTRUCTIONS, ((SystemMessage) messages.get(0)).text());
        assertEquals("show products", ((UserMessage) messages.get(1)).singleText());
        assertEquals("{\"action\":\"search\",\"index\":\"products\"}", ((AiMessage) messages.get(2)).text());
        String last = ((UserMessage) messages.get(3)).singleText();
        assertTrue(last.startsWith("(what happened with that: 3 hits)"), last);
        assertTrue(last.contains("\"products\""), last);
        assertTrue(last.endsWith("Request: now only red ones"), last);
    }

    @Test
    void withoutHistoryItIsJustRulesAndRequest() {
        FakeModel model = new FakeModel("{\"action\": \"list_indices\"}");
        new Planner(using(model)).plan("what do I have", List.of(), Map.of(), Map.of(), Map.of());
        assertEquals(2, model.seen.messages().size());
    }

    @Test
    void theDateRangesAreWorkedOutForTheModel() {
        String dates = Planner.dates();
        for (String phrase : new String[] { "today is", "this year", "last year", "this month", "last month",
                                            "the last 7 days", "the last 30 days" }) {
            assertTrue(dates.contains(phrase), phrase + " missing from: " + dates);
        }
        int year = java.time.LocalDate.now().getYear();
        assertTrue(dates.contains(year + "-01-01"), dates);
        assertTrue(dates.contains((year - 1) + "-12-31"), dates);
    }

    @Test
    void theContextEndsWithTheRequest() {
        String text = Planner.context("show me shoes", Map.of("products", Map.of()), Map.of(), Map.of());
        assertTrue(text.endsWith("Request: show me shoes"), text);
        // the dates sit just before it, where the model looks hardest
        assertTrue(text.indexOf("Dates, already worked out") > text.indexOf("Indices and their mappings"), text);
    }

    @Test
    void theFactsAboutEachIndexAreHandedOver() {
        String text = Planner.context("hi", Map.of(), Map.of("products", Map.of(
            "documents", 24,
            "values", Map.of("category", List.of("shoes", "kitchen")),
            "from lowest to highest", Map.of("price", "12.5 to 320.0"))), Map.of());
        assertTrue(text.contains("keyword fields"), text);
        assertTrue(text.contains("shoes"), text);
        assertTrue(text.contains("24"), text);
        assertTrue(text.contains("12.5 to 320.0"), text);
    }

    @Test
    void samplesOnlyShowUpWhenThereAreSome() {
        String without = Planner.context("hi", Map.of(), Map.of(), Map.of());
        assertFalse(without.contains("real document"));
        String with = Planner.context("hi", Map.of(), Map.of(), Map.of("products", Map.of("name", "Mug")));
        assertTrue(with.contains("real document"));
        assertTrue(with.contains("Mug"));
    }

    @Test
    void aDeadModelIsA502WithTheReason() {
        Planner planner = new Planner(using(new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                throw new RuntimeException("connection refused");
            }
        }));
        ElasticsearchStatusException e = assertThrows(ElasticsearchStatusException.class,
            () -> planner.plan("anything", List.of(), Map.of(), Map.of(), Map.of()));
        assertEquals(RestStatus.BAD_GATEWAY, e.status());
        assertTrue(e.getMessage().contains("connection refused"), e.getMessage());
        assertTrue(e.getMessage().contains("ollama/"), e.getMessage());
    }

    @Test
    void theRulesComeFromTheJar() {
        assertTrue(Planner.INSTRUCTIONS.contains("update_by_query"));
        assertTrue(Planner.INSTRUCTIONS.contains("reply"));
    }
}
