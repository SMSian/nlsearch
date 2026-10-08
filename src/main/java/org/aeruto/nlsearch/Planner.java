package org.aeruto.nlsearch;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Puts the chat together, asks the model, parses the answer. */
class Planner {

    // the rules and the list of actions the model may use
    static final String INSTRUCTIONS = read("prompt.txt");

    // how to turn what Elasticsearch answered back into a sentence
    static final String EXPLAIN = read("explain.txt");

    // enough of the response for the model to describe it, without filling the window
    static final int MAX_RESULT_CHARS = 6000;

    private final Models models;

    Planner(Models models) {
        this.models = models;
    }

    Plan plan(String request, List<History.Turn> history, Map<String, Object> mappings, Map<String, Object> facts,
              Map<String, Object> samples) {
        String answer;
        try {
            answer = models.get().chat(messages(request, history, mappings, facts, samples)).aiMessage().text();
        } catch (Exception e) {
            throw new ElasticsearchStatusException("could not get an answer from " + models.name() + ": " + e.getMessage(), RestStatus.BAD_GATEWAY, e);
        }
        return Plan.parse(answer);
    }

    // rules, then the earlier turns and how they went, then the request with what the cluster looks like
    static List<ChatMessage> messages(String request, List<History.Turn> history, Map<String, Object> mappings,
                                      Map<String, Object> facts, Map<String, Object> samples) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(INSTRUCTIONS));
        String happened = "";
        for (History.Turn turn : History.recent(history)) {
            messages.add(UserMessage.from(happened + turn.prompt()));
            messages.add(AiMessage.from(turn.answer()));
            happened = "(what happened with that: " + turn.outcome() + ")\n\n";
        }
        messages.add(UserMessage.from(happened + context(request, mappings, facts, samples)));
        return messages;
    }

    /**
     * What the model is told about the cluster before the request. The two things
     * it gets wrong most often, the keyword values and the dates, go last, right
     * before the request, because that is what it pays most attention to.
     */
    static String context(String request, Map<String, Object> mappings, Map<String, Object> facts, Map<String, Object> samples) {
        StringBuilder text = new StringBuilder();
        text.append("Indices and their mappings:\n").append(json(mappings)).append("\n\n");
        if (samples.isEmpty() == false) {
            text.append("One real document from each index, so you can see what the values look like:\n").append(json(samples)).append("\n\n");
        }
        if (facts.isEmpty() == false) {
            text.append("What is actually in each index: how many documents, every value the small keyword fields hold, and the span of the numbers and dates:\n")
                .append(json(facts)).append("\n")
                .append("Any word in the request that appears in one of those value lists is a term filter on that field, whatever the rest of the sentence looks like. ")
                .append("The spans tell you what the data really covers, so do not filter on a range that falls outside them.\n\n");
        }
        text.append(dates()).append("\n");
        text.append("Request: ").append(request);
        return text.toString();
    }

    /** Date ranges worked out in advance, because small models do calendar arithmetic badly. */
    static String dates() {
        LocalDate today = LocalDate.now();
        StringBuilder text = new StringBuilder("Dates, already worked out. Use these and never a year you were not given here:\n");
        text.append("  today is ").append(today).append(", so the current year is ").append(today.getYear()).append("\n");
        text.append("  this year ").append(today.withDayOfYear(1)).append(" to ").append(today.withDayOfYear(today.lengthOfYear())).append("\n");
        LocalDate lastYear = today.minusYears(1);
        text.append("  last year ").append(lastYear.withDayOfYear(1)).append(" to ").append(lastYear.withDayOfYear(lastYear.lengthOfYear())).append("\n");
        text.append("  this month ").append(today.withDayOfMonth(1)).append(" to ").append(today.withDayOfMonth(today.lengthOfMonth())).append("\n");
        LocalDate lastMonth = today.minusMonths(1);
        text.append("  last month ").append(lastMonth.withDayOfMonth(1)).append(" to ").append(lastMonth.withDayOfMonth(lastMonth.lengthOfMonth())).append("\n");
        text.append("  the last 7 days ").append(today.minusDays(7)).append(" to ").append(today).append("\n");
        text.append("  the last 30 days ").append(today.minusDays(30)).append(" to ").append(today).append("\n");
        text.append("  a month named on its own, like \"March\", means that month of ").append(today.getYear()).append("\n");
        return text.toString();
    }

    /** Turns what Elasticsearch answered into a sentence for the person who asked. */
    String explain(String request, Plan plan, String result) {
        String shown = result == null ? "nothing, the action returned no data"
            : (result.length() > MAX_RESULT_CHARS ? result.substring(0, MAX_RESULT_CHARS) + " ...(cut short)" : result);
        String question = "They asked: " + request + "\n\n"
            + "This ran: " + json(plan.toMap()) + "\n\n"
            + "Elasticsearch answered:\n" + shown + "\n\n"
            + "Now answer them.";
        try {
            return models.prose().chat(SystemMessage.from(EXPLAIN), UserMessage.from(question)).aiMessage().text().strip();
        } catch (Exception e) {
            // the action already ran; a failure to describe it must not lose the result
            return "The action ran, but " + models.name() + " could not be reached to put it into words: " + e.getMessage();
        }
    }

    static String json(Map<String, Object> map) {
        try {
            return Strings.toString(XContentFactory.jsonBuilder().map(map));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(String resource) {
        try (InputStream in = Planner.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing from the plugin jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
