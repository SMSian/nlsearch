package org.aeruto.nlsearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregation;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

/**
 * The one endpoint.
 *
 *   POST /_nl   {"prompt": "red shoes under 50", "session": "chat-123", "dry_run": false}
 *   GET  /_nl?q=red+shoes+under+50
 *
 * session is optional; keep it the same for a whole conversation and follow-ups
 * work. dry_run only shows the plan. A GET can only read.
 */
public class NLRestHandler extends BaseRestHandler {

    private static final Logger logger = LogManager.getLogger(NLRestHandler.class);

    // what the model gets to see about the cluster, in characters; the rest of the window is for the rules and the chat
    static final int MAX_CONTEXT_CHARS = 12_000;

    // a keyword field with more values than this is an id or a name, not something to list
    static final int MAX_VALUES = 40;

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

    private final Models models;
    private final Planner planner;
    private final Predicate<NodeFeature> clusterSupportsFeature;

    NLRestHandler(Models models, Predicate<NodeFeature> clusterSupportsFeature) {
        this.models = models;
        this.planner = new Planner(models);
        this.clusterSupportsFeature = clusterSupportsFeature;
    }

    @Override
    public String getName() {
        return "nlsearch";
    }

    @Override
    public List<Route> routes() {
        return List.of(new Route(POST, "/_nl"), new Route(GET, "/_nl"));
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        Map<String, Object> body = Map.of();
        if (request.hasContent()) {
            try (XContentParser parser = request.contentParser()) {
                body = parser.map();
            }
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

    /**
     * One request from start to finish: load the history, look at the cluster,
     * ask the model, run what it said, answer, remember. When Elasticsearch
     * rejects the plan the model gets one more go, and sees the failure the same
     * way it sees any earlier turn.
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
        Map<String, Object> samples;
        Map<String, Object> facts;

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
            offTheNetworkThread(() -> {
                if (session != null) {
                    history.addAll(History.load(client, session));
                }
                mappings = mappings(client);
                facts = facts(client, mappings);
                samples = samples(client, mappings.keySet());
                // a small model drowns in a huge prompt, so drop the least important part first
                if (size() > MAX_CONTEXT_CHARS) {
                    samples = Map.of();
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

        int size() {
            return Planner.json(mappings).length() + Planner.json(facts).length() + Planner.json(samples).length();
        }

        void attempt(int tries) {
            Plan plan;
            try {
                plan = planner.plan(prompt, history, mappings, facts, samples);
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
                ActionRequest request = Actions.toRequest(plan, parserConfig, clusterSupportsFeature);
                if (dryRun) {
                    finish(plan, null, RestStatus.OK, "dry run, nothing was executed");
                    return;
                }
                Actions.run(client, request, ActionListener.wrap(
                    response -> finish(plan, response, Actions.status(response), Actions.summary(response)),
                    e -> rejected(plan, e, tries)
                ));
            } catch (Exception e) {
                rejected(plan, e, tries);
            }
        }

        // we or Elasticsearch said no to the plan
        void rejected(Plan plan, Exception e, int tries) {
            history.add(new History.Turn(prompt, Planner.json(plan.toMap()), "failed: " + e.getMessage()));
            if (tries < 2 && e instanceof Actions.Refused == false) {
                offTheNetworkThread(() -> attempt(tries + 1));
            } else {
                save(() -> fail(e));
            }
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
            offTheNetworkThread(() -> {
                String explanation = null;
                if (mode.explains) {
                    if (plan.action().equals("reply")) {
                        explanation = plan.text();          // already a sentence
                    } else if (json != null) {
                        explanation = planner.explain(prompt, plan, json);
                    }
                }
                RestResponse response;
                try {
                    response = render(plan, json, explanation, status);
                } catch (Exception e) {
                    fail(e);
                    return;
                }
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
            try {
                channel.sendResponse(new RestResponse(channel, e));
            } catch (IOException inner) {
                e.addSuppressed(inner);
                logger.warn("could not send the error back to the client", e);
            }
        }

        // the model takes seconds and the ES lookups block, neither belongs on a network thread
        void offTheNetworkThread(Runnable work) {
            client.threadPool().generic().execute(new AbstractRunnable() {
                @Override
                protected void doRun() {
                    work.run();
                }

                @Override
                public void onFailure(Exception e) {
                    fail(e);
                }
            });
        }
    }

    /** Index name to mapping, for every index the model may touch. The hidden history index is not among them. */
    static Map<String, Object> mappings(NodeClient client) {
        GetMappingsRequest request = new GetMappingsRequest(Actions.MASTER_TIMEOUT).indices("*").indicesOptions(IndicesOptions.lenientExpandOpen());
        Map<String, Object> mappings = new TreeMap<>();
        client.admin().indices().getMappings(request).actionGet().mappings().forEach((name, mapping) -> mappings.put(name, mapping.sourceAsMap()));
        return mappings;
    }

    private static final Set<String> SPANNED = Set.of(
        "long", "integer", "short", "byte", "double", "float", "half_float", "scaled_float", "date");

    /**
     * What is actually in each index: how many documents, every value the small
     * keyword fields hold, and the span of the numbers and dates. Without this
     * the model has one sample document to go on and writes
     * {"match": {"name": "kitchen"}} where the data wants
     * {"term": {"category": "kitchen"}}, or filters on a year the data does not cover.
     */
    static Map<String, Object> facts(NodeClient client, Map<String, Object> mappings) {
        List<String> indices = new ArrayList<>();
        List<Map<String, String>> fieldsPerIndex = new ArrayList<>();
        MultiSearchRequest msearch = new MultiSearchRequest();
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            Map<String, String> fields = interestingFields(entry.getValue());
            if (fields.isEmpty()) {
                continue;
            }
            SearchSourceBuilder source = new SearchSourceBuilder().size(0).trackTotalHits(true);
            fields.forEach((field, type) -> {
                if (type.equals("keyword")) {
                    source.aggregation(AggregationBuilders.terms("v_" + field).field(field).size(MAX_VALUES + 1));
                } else {
                    source.aggregation(AggregationBuilders.min("lo_" + field).field(field));
                    source.aggregation(AggregationBuilders.max("hi_" + field).field(field));
                }
            });
            msearch.add(new SearchRequest(entry.getKey()).source(source));
            indices.add(entry.getKey());
            fieldsPerIndex.add(fields);
        }
        if (indices.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        MultiSearchResponse response = client.multiSearch(msearch).actionGet();
        try {
            MultiSearchResponse.Item[] items = response.getResponses();
            for (int i = 0; i < items.length && i < indices.size(); i++) {
                if (items[i].isFailure()) {
                    continue;
                }
                SearchResponse found = items[i].getResponse();
                Map<String, Object> about = new LinkedHashMap<>();
                var total = found.getHits().getTotalHits();
                if (total != null) {
                    about.put("documents", total.value());
                }
                InternalAggregations aggregations = found.getAggregations();
                if (aggregations != null) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    Map<String, Object> spans = new LinkedHashMap<>();
                    for (Map.Entry<String, String> field : fieldsPerIndex.get(i).entrySet()) {
                        String name = field.getKey();
                        if (field.getValue().equals("keyword")) {
                            if (aggregations.get("v_" + name) instanceof Terms terms) {
                                List<String> list = terms.getBuckets().stream().map(Terms.Bucket::getKeyAsString).toList();
                                // more than the cap means it is an id or a name, not a set worth listing
                                if (list.isEmpty() == false && list.size() <= MAX_VALUES) {
                                    values.put(name, list);
                                }
                            }
                        } else {
                            String low = single(aggregations, "lo_" + name);
                            String high = single(aggregations, "hi_" + name);
                            if (low != null && high != null) {
                                spans.put(name, low.equals(high) ? low : low + " to " + high);
                            }
                        }
                    }
                    if (values.isEmpty() == false) {
                        about.put("values", values);
                    }
                    if (spans.isEmpty() == false) {
                        about.put("from lowest to highest", spans);
                    }
                }
                facts.put(indices.get(i), about);
            }
        } finally {
            response.decRef();
        }
        return facts;
    }

    /** A min or max as the field itself would show it, so a date reads as a date. */
    private static String single(InternalAggregations aggregations, String name) {
        InternalAggregation aggregation = aggregations.get(name);
        if (aggregation instanceof NumericMetricsAggregation.SingleValue value) {
            // a field with no values at all comes back as an infinity, and asking such an
            // aggregation to format itself throws rather than returning anything useful
            double raw = value.value();
            if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                return null;
            }
            return value.getValueAsString();
        }
        return null;
    }

    /** Field name to type, for the fields worth summarising. */
    private static Map<String, String> interestingFields(Object mapping) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (mapping instanceof Map<?, ?> map && map.get("properties") instanceof Map<?, ?> properties) {
            properties.forEach((name, definition) -> {
                if (definition instanceof Map<?, ?> field) {
                    Object type = field.get("type");
                    if ("keyword".equals(type) || SPANNED.contains(String.valueOf(type))) {
                        fields.put(String.valueOf(name), String.valueOf(type));
                    }
                }
            });
        }
        return fields;
    }

    /** One document from each index, trimmed, so the model sees what the values look like. */
    static Map<String, Object> samples(NodeClient client, Collection<String> indices) {
        if (indices.isEmpty()) {
            return Map.of();
        }
        List<String> names = new ArrayList<>(indices);
        MultiSearchRequest msearch = new MultiSearchRequest();
        for (String name : names) {
            msearch.add(new SearchRequest(name).source(new SearchSourceBuilder().size(1)));
        }

        Map<String, Object> samples = new LinkedHashMap<>();
        MultiSearchResponse response = client.multiSearch(msearch).actionGet();
        try {
            MultiSearchResponse.Item[] items = response.getResponses();
            for (int i = 0; i < items.length; i++) {
                if (items[i].isFailure()) {
                    continue;
                }
                SearchHit[] hits = items[i].getResponse().getHits().getHits();
                if (hits.length > 0 && hits[0].getSourceAsMap() != null) {
                    samples.put(names.get(i), shorten(hits[0].getSourceAsMap()));
                }
            }
        } finally {
            response.decRef();
        }
        return samples;
    }

    // long texts and big arrays only cost tokens
    static Object shorten(Object value) {
        if (value instanceof String s && s.length() > 80) {
            return s.substring(0, 80) + "...";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), shorten(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().limit(5).map(NLRestHandler::shorten).toList();
        }
        return value;
    }

    private static String text(Object fromBody, String fromUrl) {
        String value = fromBody == null ? fromUrl : String.valueOf(fromBody);
        return value == null || value.isBlank() ? null : value;
    }
}
