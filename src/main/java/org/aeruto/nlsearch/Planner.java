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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Puts the chat together, asks the model, parses the answer. */
class Planner {

    // the rules and the list of actions the model may use
    static final String INSTRUCTIONS = read("prompt.txt");

    // how to turn what Elasticsearch answered back into a sentence
    static final String EXPLAIN = read("explain.txt");

    // how to work out what an index actually means
    static final String ANALYZE = read("analyze.txt");

    // enough of the response for the model to describe it, without filling the window
    static final int MAX_RESULT_CHARS = 6000;

    private final Models models;

    Planner(Models models) {
        this.models = models;
    }

    Plan plan(String request, List<History.Turn> history, Map<String, Object> mappings, Map<String, Object> facts,
              Map<String, Object> samples, Map<String, String> briefings) {
        String answer;
        try {
            answer = models.get().chat(messages(request, history, mappings, facts, samples, briefings)).aiMessage().text();
        } catch (Exception e) {
            throw new ElasticsearchStatusException("could not get an answer from " + models.name() + ": " + e.getMessage(), RestStatus.BAD_GATEWAY, e);
        }
        return Plan.parse(answer);
    }

    // rules, then the earlier turns and how they went, then the request with what the cluster looks like
    static List<ChatMessage> messages(String request, List<History.Turn> history, Map<String, Object> mappings,
                                      Map<String, Object> facts, Map<String, Object> samples, Map<String, String> briefings) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(INSTRUCTIONS));
        String happened = "";
        String lastOutcome = "";
        for (History.Turn turn : History.recent(history)) {
            messages.add(UserMessage.from(happened + turn.prompt()));
            messages.add(AiMessage.from(turn.answer()));
            happened = "(what happened with that: " + turn.outcome() + ")\n\n";
            lastOutcome = turn.outcome();
        }
        messages.add(UserMessage.from(context(request, mappings, facts, samples, briefings, lastOutcome, previous(history))));
        return messages;
    }

    /**
     * The index the conversation is already about.
     *
     * Saying this out loud is only necessary because of the briefings. One of them
     * will state plainly that "out of stock" means qty_on_hand 0 in some warehouse
     * index, and those words then pull a follow-up away from the index the user was
     * actually looking at. The rule is in prompt.txt as well, but a rule two hundred
     * lines up competes badly with a briefing; the name of the index, given here,
     * does not have to compete with anything.
     */
    static Plan previous(List<History.Turn> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            History.Turn turn = history.get(i);
            // never build on a turn that did not work. A retry is told not to send the same
            // JSON again, and quoting the JSON that just failed as the thing to start from
            // says the opposite, louder and closer to the request. That cost several
            // retries their entire purpose before it was spotted.
            if (worked(turn.outcome()) == false) {
                continue;
            }
            try {
                Plan plan = Plan.parse(turn.answer());
                if (plan.index() != null && plan.index().isBlank() == false) {
                    return plan;
                }
            } catch (Exception e) {
                // an answer we can no longer read tells us nothing; look further back
            }
        }
        return null;
    }

    /** An outcome the plugin wrote to say the attempt went nowhere. */
    static boolean worked(String outcome) {
        return outcome == null || (outcome.startsWith("failed:") == false && outcome.startsWith("found nothing") == false);
    }

    /** The index of the last answer that named one, or null. */
    static String staying(List<History.Turn> history) {
        Plan plan = previous(history);
        return plan == null ? null : plan.index();
    }

    /**
     * What the model is told about the cluster before the request. The two things
     * it gets wrong most often, the keyword values and the dates, go late, close to
     * the request, because that is what it pays most attention to. The request itself
     * stays last of all, with the two things that have to beat everything above
     * them immediately before it: which index the conversation is about, and how
     * the last answer went.
     */
    static String context(String request, Map<String, Object> mappings, Map<String, Object> facts, Map<String, Object> samples) {
        return context(request, mappings, facts, samples, Map.of());
    }

    static String context(String request, Map<String, Object> mappings, Map<String, Object> facts,
                          Map<String, Object> samples, Map<String, String> briefings) {
        return context(request, mappings, facts, samples, briefings, "", null);
    }

    static String context(String request, Map<String, Object> mappings, Map<String, Object> facts,
                          Map<String, Object> samples, Map<String, String> briefings,
                          String lastOutcome, Plan previous) {
        StringBuilder text = new StringBuilder();
        if (briefings.isEmpty() == false) {
            // the most valuable thing here: what the fields and the coded values actually mean
            text.append("What these indices hold, worked out from the data itself. Trust this over your own reading of a field name:\n");
            briefings.forEach((index, briefing) -> text.append("\n").append(index).append("\n").append(briefing).append("\n"));
            text.append("\n");
        }
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
        if (previous != null) {
            text.append("The conversation so far has been about the index \"").append(previous.index())
                .append("\". If the request below is a follow-up, it is about that index too. ")
                .append("It is not, if the request names another index, or asks about the cluster rather than ")
                .append("documents in it. This settles which index, never what to do: that comes from the request.\n");
            if (previous.action().equals("search") && previous.body() != null && previous.body().isEmpty() == false) {
                // the body, not just the index: "now only the ones in stock" has to start from the
                // query it is narrowing, and quoting it beats hoping the model rereads the chat.
                // Searches only. Putting a delete's body this close to the next request is how you
                // get "delete the products index" answered by repeating the last delete.
                text.append("The body you sent last time was ").append(json(previous.body()))
                    .append(". A follow-up starts from that and changes only what the request asks for;")
                    .append(" anything the request does not mention stays exactly as it is. Changing it includes")
                    .append(" adding what the new request needs, such as a sort, a size or an aggregation.\n");
            }
            text.append("\n");
        }
        if (lastOutcome != null && lastOutcome.isEmpty() == false) {
            text.append(followUp(lastOutcome)).append("\n\n");
        }
        text.append("Request: ").append(request);
        return text.toString();
    }

    /**
     * How the previous answer went, said immediately before the request.
     *
     * This used to be the first line of this message, which is a long way from the
     * request once the briefings, the mappings and the facts sit in between. A retry
     * read straight past it and sent the identical broken query a second time, which
     * is a wasted model call and a 400 for the user either way.
     *
     * It does not go after the request either. That was tried, and the model started
     * treating the outcome as the thing to respond to: told "document 1 deleted" last,
     * it answered "delete the products index" by deleting document 1 again. Whatever
     * comes last is what gets answered, so the request comes last.
     */
    private static String followUp(String outcome) {
        if (outcome.startsWith("failed:")) {
            return "Your own previous answer, just above, " + outcome
                + "\nFix whatever caused that. Do not send the same JSON again.";
        }
        return "(what happened with your previous answer: " + outcome + ")";
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

    /**
     * Works out what these indices are for and what their fields and coded values
     * mean, all in one call. Done once and stored, because it is the expensive
     * part and it only changes when the data does. Taking the whole cluster at
     * once costs one request instead of one per index, and lets the answer say
     * how the indices relate.
     */
    Map<String, String> analyse(Map<String, Object> mappings, Map<String, Object> facts,
                                Map<String, List<Map<String, Object>>> examples) {
        StringBuilder about = new StringBuilder();
        for (Map.Entry<String, Object> index : mappings.entrySet()) {
            String name = index.getKey();
            about.append("### ").append(name).append("\n")
                 .append("Mapping: ").append(json(asMap(index.getValue()))).append("\n")
                 .append("What is in it: ").append(json(asMap(facts.get(name)))).append("\n")
                 .append("Documents, chosen to cover each keyword value: ")
                 .append(json(Map.of("documents", examples.getOrDefault(name, List.of())))).append("\n\n");
        }
        about.append("Now write one briefing per index, each starting with its ## line.");
        String answer;
        try {
            answer = models.study(mappings.size())
                .chat(SystemMessage.from(ANALYZE), UserMessage.from(about.toString())).aiMessage().text();
        } catch (Exception e) {
            // without this it surfaces as a 500 and a langchain4j stack trace, which tells nobody what to do
            throw new ElasticsearchStatusException("could not work out what the indices hold, asking " + models.name()
                + ": " + e.getMessage() + "; analysing one index at a time ({\"index\": \"...\"}) asks less of it,"
                + " and nlsearch.timeout is what bounds each one", RestStatus.BAD_GATEWAY, e);
        }
        return split(answer, mappings.keySet());
    }

    /** Cuts the one answer back into a briefing per index, on its "## name" lines. */
    static Map<String, String> split(String answer, Collection<String> indices) {
        Map<String, String> briefings = new LinkedHashMap<>();
        String current = null;
        StringBuilder text = new StringBuilder();
        for (String line : answer.split("\n", -1)) {
            String heading = line.strip().replaceFirst("^#+\\s*", "");
            if (line.strip().startsWith("#") && indices.contains(heading)) {
                if (current != null) {
                    briefings.put(current, text.toString().strip());
                }
                current = heading;
                text.setLength(0);
            } else if (current != null) {
                text.append(line).append("\n");
            }
        }
        if (current != null) {
            briefings.put(current, text.toString().strip());
        }
        briefings.values().removeIf(String::isEmpty);
        return briefings;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
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
