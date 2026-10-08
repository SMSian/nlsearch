package org.aeruto.nlsearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

/**
 * The endpoints.
 *
 *   POST /_nl          {"prompt": "...", "session": "...", "dry_run": false, "response": "both"}
 *   GET  /_nl?q=...    read-only
 *   POST /_nl/analyze  {"index": "...", "session": "...", "force": false}
 *   GET  /_nl/analyze  what has already been worked out
 *
 * A request costs two calls to the model: one to decide what to do, one to put
 * the answer into words. Ask for "raw" and it costs one. Working out what an
 * index means costs one more, the first time only, and is then stored.
 */
public class NLRestHandler extends BaseRestHandler {

    private static final Logger logger = LogManager.getLogger(NLRestHandler.class);

    /** What the model is told about the cluster, in characters. The rest of the window is for the rules and the chat. */
    static final int MAX_CONTEXT_CHARS = 12_000;

    /** Actions whose whole effect comes from a query, so a wrong field name silently does nothing. */
    private static final Set<String> CHECKED = Set.of("search", "update_by_query", "delete_by_query");

    private final Models models;
    private final Planner planner;
    private final Predicate<NodeFeature> clusterSupportsFeature;
    private volatile Settings settings;

    NLRestHandler(Models models, Settings settings, Predicate<NodeFeature> clusterSupportsFeature) {
        this.models = models;
        this.planner = new Planner(models);
        this.settings = settings;
        this.clusterSupportsFeature = clusterSupportsFeature;
    }

    void reload(Settings changed) {
        settings = changed;
    }

    @Override
    public String getName() {
        return "nlsearch";
    }

    @Override
    public List<Route> routes() {
        return List.of(
            new Route(POST, "/_nl"),
            new Route(GET, "/_nl"),
            new Route(POST, "/_nl/analyze"),
            new Route(GET, "/_nl/analyze")
        );
    }

    /**
     * What the caller wants back. "both" by default: a sentence to show someone
     * and the Elasticsearch response to work with. "explain" for the sentence on
     * its own, "raw" for the data on its own and no second call to the model.
     */
    enum Mode {
        RAW(true, false),
        EXPLAIN(false, true),
        BOTH(true, true);

        final boolean keepsResult;
        final boolean explains;

        Mode(boolean keepsResult, boolean explains) {
            this.keepsResult = keepsResult;
            this.explains = explains;
        }

        static Mode of(String asked) {
            if (asked == null || asked.isBlank()) {
                return BOTH;
            }
            return switch (asked.toLowerCase(Locale.ROOT)) {
                case "raw" -> RAW;
                case "explain" -> EXPLAIN;
                case "both", "default" -> BOTH;
                default -> throw new IllegalArgumentException(
                    "\"response\" has to be \"both\" (the default), \"explain\" or \"raw\", not [" + asked + "]");
            };
        }
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        Map<String, Object> body = body(request);
        if (request.path().endsWith("/analyze")) {
            String only = text(body.get("index"), request.param("index"));
            String session = text(body.get("session"), request.param("session"));
            boolean force = request.paramAsBoolean("force", false) || "true".equals(String.valueOf(body.get("force")));
            boolean readOnly = request.method() == GET;
            return channel -> offTheNetworkThread(client, channel,
                () -> analyze(client, channel, only, session, force, readOnly));
        }

        String prompt = text(body.get("prompt"), request.param("prompt", request.param("q")));
        String session = text(body.get("session"), request.param("session"));
        boolean dryRun = request.paramAsBoolean("dry_run", false) || "true".equals(String.valueOf(body.get("dry_run")));
        Mode mode = Mode.of(text(body.get("response"), request.param("response")));
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("say what you want: {\"prompt\": \"...\"} in the body, or ?q=... in the url");
        }
        boolean readOnly = request.method() == GET;
        XContentParserConfiguration parserConfig = request.contentParserConfig(); // knows every query and aggregation type
        return channel -> new Conversation(client, channel, prompt, session, dryRun, readOnly, mode, parserConfig).start();
    }

    private static Map<String, Object> body(RestRequest request) throws IOException {
        if (request.hasContent() == false) {
            return Map.of();
        }
        try (XContentParser parser = request.contentParser()) {
            return parser.map();
        }
    }

    // ---------------------------------------------------------------- analyze

    /**
     * Works out what every index holds and what its coded values mean, and keeps
     * the answer. One call to the model covers the whole cluster, so it also sees
     * how the indices relate to each other.
     */
    private void analyze(NodeClient client, RestChannel channel, String only, String session, boolean force, boolean readOnly)
        throws IOException {
        Map<String, Object> mappings = Indices.mappings(client);
        if (only != null) {
            if (mappings.containsKey(only) == false) {
                throw new IllegalArgumentException("there is no index called [" + only + "]");
            }
            mappings.keySet().retainAll(List.of(only));
        }
        if (mappings.isEmpty()) {
            throw new IllegalArgumentException("there are no indices to look at");
        }

        Map<String, String> briefings = new LinkedHashMap<>();
        List<String> todo = new ArrayList<>();
        Map<String, Analysis.Profile> stored = Analysis.load(client, mappings.keySet(), session);
        for (Map.Entry<String, Object> index : mappings.entrySet()) {
            Analysis.Profile profile = stored.get(index.getKey());
            boolean usable = profile != null && force == false
                && profile.stale(Analysis.fingerprint(index.getValue()), Instant.now()) == false;
            if (usable) {
                briefings.put(index.getKey(), profile.summary());
            } else if (readOnly) {
                briefings.put(index.getKey(), profile == null ? "(not looked at yet)" : profile.summary() + "\n(out of date)");
            } else {
                todo.add(index.getKey());
            }
        }

        int written = 0;
        if (todo.isEmpty() == false) {
            Map<String, Object> wanted = new LinkedHashMap<>();
            todo.forEach(name -> wanted.put(name, mappings.get(name)));
            Map<String, String> fresh = study(client, wanted, session);
            briefings.putAll(fresh);
            written = fresh.size();
            // study skips empty indices; say so rather than leaving one out of the answer
            todo.forEach(name -> briefings.putIfAbsent(name, "(nothing in it yet, so there is nothing to work out)"));
        }

        try (XContentBuilder out = channel.newBuilder()) {
            out.startObject();
            out.field("model", models.name());
            if (session != null) {
                out.field("session", session);
            }
            out.field("analysed", written);
            out.field("indices", briefings);
            out.endObject();
            channel.sendResponse(new RestResponse(RestStatus.OK, out));
        }
    }

    /**
     * Looks at these indices, writes down what they mean, and stores it.
     *
     * An empty index is skipped. There is nothing in it to read a meaning from, and a
     * briefing written from no documents would be stored and then trusted for as long
     * as a real one, which is how an index created a minute ago ends up permanently
     * described as unknowable. Left alone it is analysed properly on the first
     * question after something is actually in it.
     */
    private Map<String, String> study(NodeClient client, Map<String, Object> mappings, String session) {
        Map<String, Object> facts = Indices.facts(client, mappings);
        mappings.keySet().removeIf(name -> Indices.documents(facts.get(name)) == 0);
        if (mappings.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Map<String, Object>>> examples = new LinkedHashMap<>();
        mappings.forEach((name, mapping) -> examples.put(name, Indices.examples(client, name, mapping)));

        Map<String, String> briefings = planner.analyse(mappings, facts, examples);
        // An index whose values are already plain English has nothing to decode, and the
        // model rightly writes nothing for it. Store that as an answer rather than a gap:
        // a gap looks like "not analysed yet" and would be re-analysed on every request.
        for (String name : mappings.keySet()) {
            briefings.computeIfAbsent(name, n -> Analysis.NOTHING_TO_DECODE);
        }
        Instant now = Instant.now();
        Instant expires = now.plusMillis(NLSettings.ANALYSIS_TTL.get(settings).millis());
        briefings.forEach((name, summary) -> Analysis.save(client,
            new Analysis.Profile(name, summary, Analysis.fingerprint(mappings.get(name)), now.toString(), expires.toString()),
            session, () -> {}));
        return briefings;
    }

    // ----------------------------------------------------------- one request

    /**
     * One request from start to finish: find out what we know, ask the model,
     * run what it said, put the answer into words, remember the turn. When
     * Elasticsearch rejects the plan the model gets one more go, and sees the
     * failure the way it sees any earlier turn.
     */
    private class Conversation {
        final NodeClient client;
        final RestChannel channel;
        final String prompt;
        final String session;
        final boolean dryRun;
        final boolean readOnly;
        final Mode mode;
        final XContentParserConfiguration parserConfig;
        final List<History.Turn> history = new ArrayList<>();
        Map<String, Object> mappings;
        Map<String, Object> facts;
        Map<String, Object> samples = Map.of();
        Map<String, String> briefings = Map.of();

        Conversation(NodeClient client, RestChannel channel, String prompt, String session, boolean dryRun, boolean readOnly,
                     Mode mode, XContentParserConfiguration parserConfig) {
            this.client = client;
            this.channel = channel;
            this.prompt = prompt;
            this.session = session;
            this.dryRun = dryRun;
            this.readOnly = readOnly;
            this.mode = mode;
            this.parserConfig = parserConfig;
        }

        void start() {
            offTheNetworkThread(client, channel, () -> {
                if (session != null) {
                    history.addAll(History.load(client, session));
                }
                mappings = Indices.mappings(client);
                facts = Indices.facts(client, mappings);
                briefings = known();
                // an index whose values already say what they mean has a briefing on record so it
                // is not analysed again, but sending it costs context and teaches nothing
                briefings.values().removeIf(Analysis.NOTHING_TO_DECODE::equals);

                // a briefing says what a sample document could only hint at, so one or the other
                if (briefings.keySet().containsAll(mappings.keySet()) == false) {
                    samples = Indices.samples(client, mappings.keySet());
                }
                // A small model drowns in a huge prompt, so drop the least valuable part first.
                // Briefings go before facts, and that order was learned the hard way: with it the
                // other way round, four indices' briefings filled the budget, the keyword values
                // were dropped to make room, and the model went back to matching "kitchen" against
                // a name field. A briefing explains what a value means; the facts say what the
                // values are. A query needs the second.
                if (size() > MAX_CONTEXT_CHARS) {
                    samples = Map.of();
                }
                if (size() > MAX_CONTEXT_CHARS) {
                    briefings = Map.of();
                }
                if (size() > MAX_CONTEXT_CHARS) {
                    facts = Map.of();
                }
                if (size() > MAX_CONTEXT_CHARS) {
                    mappings.replaceAll((name, mapping) -> "(fields not shown, too many indices)");
                }
                attempt(1);
            });
        }

        /**
         * What has already been worked out, bringing anything missing up to date
         * first. This is the point of the whole thing: without it the model is
         * guessing at what a field called dept means.
         */
        Map<String, String> known() {
            Map<String, Analysis.Profile> stored = Analysis.load(client, mappings.keySet(), session);
            Map<String, String> found = new LinkedHashMap<>();
            Map<String, Object> missing = new LinkedHashMap<>();
            Instant now = Instant.now();
            mappings.forEach((name, mapping) -> {
                Analysis.Profile profile = stored.get(name);
                if (profile != null && profile.stale(Analysis.fingerprint(mapping), now) == false) {
                    found.put(name, profile.summary());
                } else {
                    missing.put(name, mapping);
                }
            });
            if (missing.isEmpty()) {
                return found;
            }
            try {
                found.putAll(study(client, missing, null));   // shared, not tied to this conversation
            } catch (Exception e) {
                // worth one try; a request must not fail because we could not describe an index
                logger.warn("could not work out what " + missing.keySet() + " hold, going on without it", e);
            }
            return found;
        }

        int size() {
            return Planner.json(mappings).length() + Planner.json(facts).length() + Planner.json(samples).length()
                + briefings.values().stream().mapToInt(String::length).sum();
        }

        void attempt(int tries) {
            Plan plan;
            try {
                plan = planner.plan(prompt, history, mappings, facts, samples, briefings);
            } catch (Exception e) {
                fail(e); // the model is down or talks nonsense, asking again will not help
                return;
            }
            try {
                if (plan.action().equals("reply")) {
                    finish(plan, null, RestStatus.OK, "replied");
                    return;
                }
                if (readOnly && Actions.READ_ONLY.contains(plan.action()) == false) {
                    throw new Actions.Refused("a GET can only read; use POST to change data");
                }
                unknownFields(plan);
                ActionRequest request = Actions.toRequest(plan, parserConfig, clusterSupportsFeature);
                if (dryRun) {
                    finish(plan, null, RestStatus.OK, "dry run, nothing was executed");
                    return;
                }
                Actions.run(client, request, ActionListener.wrap(
                    response -> {
                        String hint = tries < 2 && Actions.hits(response) == 0 ? searchedTheWrongField(plan) : null;
                        if (hint != null) {
                            history.add(new History.Turn(prompt, Planner.json(plan.toMap()), "found nothing. " + hint));
                            offTheNetworkThread(client, channel, () -> attempt(tries + 1));
                            return;
                        }
                        finish(plan, response, Actions.status(response), Actions.summary(response));
                    },
                    e -> rejected(plan, e, tries)
                ));
            } catch (Exception e) {
                rejected(plan, e, tries);
            }
        }

        /**
         * Why a search that found nothing probably found nothing.
         *
         * The commonest wrong query in this whole plugin searches a text field for a word
         * that is a value of a keyword field: "shoes" against name, when shoes is a
         * category. It is valid, it matches nothing, and an empty result reads as an
         * answer. The rules say not to, the values are in the prompt, and a 7B model does
         * it anyway.
         *
         * So this is checked after the fact rather than before: no documents came back,
         * and a word being searched for is exactly a value of some keyword field that the
         * query never filtered on. Only then, and only once. Nothing is refused and no
         * correct query is ever blocked, because by this point we know the answer was
         * empty either way.
         */
        @SuppressWarnings("unchecked")
        String searchedTheWrongField(Plan plan) {
            if (facts.get(plan.index()) instanceof Map<?, ?> about
                && about.get("values") instanceof Map<?, ?> values) {
                Map<String, String> looking = Actions.matched(plan.body());
                for (Map.Entry<String, String> each : looking.entrySet()) {
                    for (String word : each.getValue().toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                        for (Map.Entry<?, ?> field : values.entrySet()) {
                            String name = String.valueOf(field.getKey());
                            // whether the right filter is also present does not matter: the text
                            // search is impossible either way, and it is what returned nothing
                            if (name.equals(each.getKey()) || word.isEmpty()) {
                                continue;
                            }
                            if (field.getValue() instanceof List<?> held && held.stream()
                                    .anyMatch(value -> String.valueOf(value).toLowerCase(Locale.ROOT).equals(word))) {
                                return "\"" + word + "\" is a value of the keyword field [" + name + "], not a word in ["
                                    + each.getKey() + "]. Replace that text search with a term filter on [" + name + "]."
                                    + " Adding the filter and keeping the text search leaves the same impossible condition in place,"
                                    + " so take the text search out unless what is left of it is really part of a name.";
                            }
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Stop a plan that filters, sorts or aggregates on a field the index does not have.
         *
         * Elasticsearch runs such a query happily and returns nothing, which is the one
         * failure nobody notices: no error, a tidy empty result, and an explanation that
         * confidently says there were none. Caught here it becomes an ordinary rejection,
         * so the model gets one more go with the real field names in front of it.
         *
         * Only for reads of one existing index. A write may legitimately introduce a field,
         * and a plan with no index or a wildcard has no single mapping to check against.
         */
        void unknownFields(Plan plan) {
            if (CHECKED.contains(plan.action()) == false || plan.index() == null) {
                return;
            }
            Object mapping = mappings.get(plan.index());
            if (mapping == null) {
                // a category or a brand used as an index name. Elasticsearch answers 404,
                // which reaches the user as a stack-shaped error about an index they never
                // mentioned, so say what the indices actually are instead
                throw new IllegalArgumentException("there is no index called [" + plan.index()
                    + "]. The indices are: " + String.join(", ", mappings.keySet())
                    + ". A category, a brand or any other field value is never an index name.");
            }
            String contradiction = Actions.contradiction(plan.body());
            if (contradiction != null) {
                throw new IllegalArgumentException("the query both requires and excludes [" + contradiction
                    + "], so nothing can ever match it. Asking for everything except something is one condition,"
                    + " a must_not, not a must and a must_not together.");
            }
            Set<String> known = Indices.fields(mapping);
            if (known.isEmpty()) {
                return;
            }
            List<String> missing = Actions.fieldsUsed(plan.body()).stream()
                .filter(field -> field.startsWith("_") == false)
                .filter(field -> known.contains(field) == false)
                .toList();
            if (missing.isEmpty() == false) {
                throw new IllegalArgumentException("[" + plan.index() + "] has no field "
                    + String.join(" or ", missing) + ". Its fields are: " + String.join(", ", known)
                    + ". Use one of those, or reply and say the data does not hold what was asked for.");
            }
        }

        // we or Elasticsearch said no to the plan
        void rejected(Plan plan, Exception e, int tries) {
            String why = Actions.why(e);
            history.add(new History.Turn(prompt, Planner.json(plan.toMap()), "failed: " + why));
            if (tries < allowed(why) && e instanceof Actions.Refused == false) {
                offTheNetworkThread(client, channel, () -> attempt(tries + 1));
            } else {
                save(() -> fail(e));
            }
        }

        /**
         * How many attempts this kind of failure is worth.
         *
         * One retry for most things. Two when the JSON itself would not parse, because
         * then nothing ran and nothing was judged: the model simply built the object
         * wrongly, which is the one failure where trying again is pure upside and the
         * second attempt is often right. A plan that ran and gave a poor answer gets no
         * extra go, since repeating it only costs time.
         */
        int allowed(String why) {
            String reason = why == null ? "" : why.toLowerCase(Locale.ROOT);
            boolean couldNotBeRead = reason.contains("parse") || reason.contains("malformed")
                || reason.contains("unknown key") || reason.contains("expected [");
            return couldNotBeRead ? 3 : 2;
        }

        void finish(Plan plan, ActionResponse result, RestStatus status, String outcome) {
            String json;
            try {
                // read straight away: the Elasticsearch response is only ours until this listener returns
                json = result == null ? null : Actions.json(result);
            } catch (Exception e) {
                fail(e); // the action already ran, so whatever happens, no second attempt
                return;
            }
            history.add(new History.Turn(prompt, Planner.json(plan.toMap()), outcome));

            // putting the answer into words is another model call, which cannot run on a transport thread
            offTheNetworkThread(client, channel, () -> {
                String explanation = null;
                if (mode.explains) {
                    if (plan.action().equals("reply")) {
                        explanation = plan.text();          // already a sentence
                    } else if (json != null) {
                        explanation = planner.explain(prompt, plan, json);
                    }
                }
                RestResponse response = render(plan, json, explanation, status);
                save(() -> channel.sendResponse(response));
            });
        }

        RestResponse render(Plan plan, String json, String explanation, RestStatus status) throws IOException {
            try (XContentBuilder out = channel.newBuilder()) {
                out.startObject();
                out.field("model", models.name());
                if (session != null) {
                    out.field("session", session);
                }
                for (Map.Entry<String, Object> field : plan.toMap().entrySet()) {
                    out.field(field.getKey(), field.getValue());
                }
                if (dryRun) {
                    out.field("dry_run", true);
                }
                if (explanation != null) {
                    out.field("response", explanation);
                }
                if (json != null && mode.keepsResult) {
                    out.rawField("result", new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), XContentType.JSON);
                }
                out.endObject();
                return new RestResponse(status, out);
            }
        }

        /**
         * Stores the turns and then answers. A bot sends the next turn the moment
         * it has the answer, so the turn has to be readable by then.
         */
        void save(Runnable then) {
            if (session == null) {
                then.run();
            } else {
                History.save(client, session, history, then);
            }
        }

        void fail(Exception e) {
            send(channel, e);
        }
    }

    // the model takes seconds and the lookups block, neither belongs on a network thread
    private static void offTheNetworkThread(NodeClient client, RestChannel channel, Work work) {
        client.threadPool().generic().execute(new AbstractRunnable() {
            @Override
            protected void doRun() throws Exception {
                work.run();
            }

            @Override
            public void onFailure(Exception e) {
                send(channel, e);
            }
        });
    }

    @FunctionalInterface
    private interface Work {
        void run() throws Exception;
    }

    private static void send(RestChannel channel, Exception e) {
        try {
            channel.sendResponse(new RestResponse(channel, e));
        } catch (IOException inner) {
            e.addSuppressed(inner);
            logger.warn("could not send the error back to the client", e);
        }
    }

    private static String text(Object fromBody, String fromUrl) {
        String value = fromBody == null ? fromUrl : String.valueOf(fromBody);
        return value == null || value.isBlank() ? null : value;
    }
}
