package org.aeruto.nlsearch;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.ChunkedToXContent;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.reindex.BulkByPaginatedSearchResponse;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.index.reindex.UpdateByQueryAction;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.ToXContentFragment;
import org.elasticsearch.xcontent.ToXContentObject;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Plan in, Elasticsearch request out, then run it. Building and running are
 * separate so the building part can be unit tested.
 */
final class Actions {

    static final TimeValue MASTER_TIMEOUT = TimeValue.timeValueSeconds(30);

    /** The only actions a GET may run. */
    static final Set<String> READ_ONLY = Set.of("search", "get_mapping", "list_indices", "reply");

    /** We said no on purpose. The model does not get to try something else instead. */
    static class Refused extends IllegalArgumentException {
        Refused(String message) {
            super(message);
        }
    }

    private Actions() {}

    @SuppressWarnings("unchecked")
    static ActionRequest toRequest(Plan plan, XContentParserConfiguration parserConfig, Predicate<NodeFeature> clusterSupportsFeature)
        throws IOException {
        if (History.INDEX.equals(plan.index())) {
            throw new Refused("[" + History.INDEX + "] is where nlsearch keeps the chat history, leave it alone");
        }
        switch (plan.action()) {
            case "search": {
                SearchRequest request = new SearchRequest(plan.index() == null ? new String[] { "*" } : Strings.splitStringByCommaToArray(plan.index()));
                try (XContentParser parser = XContentHelper.mapToXContentParser(parserConfig, body(plan))) {
                    request.source(new SearchSourceBuilder().parseXContent(parser, true, clusterSupportsFeature));
                }
                return request;
            }
            case "index":
                return document(index(plan), plan.id(), body(plan)).setRefreshPolicy(RefreshPolicy.IMMEDIATE);
            case "bulk": {
                BulkRequest bulk = new BulkRequest().setRefreshPolicy(RefreshPolicy.IMMEDIATE);
                for (Object doc : need(plan.docs(), "docs", plan)) {
                    if (doc instanceof Map == false) {
                        throw bad("every entry of \"docs\" has to be a document, got [" + doc + "]");
                    }
                    bulk.add(document(index(plan), null, (Map<String, Object>) doc));
                }
                return bulk;
            }
            case "update": {
                UpdateRequest request = new UpdateRequest(index(plan), id(plan)).setRefreshPolicy(RefreshPolicy.IMMEDIATE);
                Map<String, Object> body = body(plan);
                if (body.get("script") != null) {
                    request.script(Script.parse(body.get("script")));
                } else if (body.size() == 1 && body.get("doc") instanceof Map<?, ?> doc) {
                    request.doc((Map<String, Object>) doc); // written like the real update api, fine
                } else {
                    request.doc(body);
                }
                return request;
            }
            case "delete":
                return new DeleteRequest(index(plan), id(plan)).setRefreshPolicy(RefreshPolicy.IMMEDIATE);
            case "update_by_query": {
                UpdateByQueryRequest request = new UpdateByQueryRequest(oneIndex(plan));
                request.setQuery(query(plan, parserConfig));
                Object script = body(plan).get("script");
                if (script != null) {
                    request.setScript(Script.parse(script));
                }
                request.setRefresh(true);
                return request;
            }
            case "delete_by_query": {
                DeleteByQueryRequest request = new DeleteByQueryRequest(oneIndex(plan));
                request.setQuery(query(plan, parserConfig));
                request.setRefresh(true);
                return request;
            }
            case "create_index": {
                // not source(map): that one expects the mappings wrapped in a type name, like the REST layer does
                Map<String, Object> body = indexBody(plan.body());
                CreateIndexRequest request = new CreateIndexRequest(index(plan));
                if (body.get("settings") instanceof Map<?, ?> settings) {
                    request.settings((Map<String, ?>) settings);
                }
                if (body.get("mappings") instanceof Map<?, ?> mappings) {
                    request.mapping((Map<String, ?>) mappings);
                }
                if (body.get("aliases") instanceof Map<?, ?> aliases) {
                    request.aliases((Map<String, ?>) aliases);
                }
                return request;
            }
            case "delete_index":
                return new DeleteIndexRequest(oneIndex(plan));
            case "get_mapping":
            case "list_indices":
                return new GetMappingsRequest(MASTER_TIMEOUT).indices(plan.index() == null ? "*" : plan.index());
            case "put_mapping":
                return new PutMappingRequest(oneIndex(plan)).source(mappingBody(body(plan)));
            default:
                throw bad("the model picked an action that does not exist: [" + plan.action() + "]");
        }
    }

    static void run(NodeClient client, ActionRequest request, ActionListener<ActionResponse> listener) {
        if (request instanceof SearchRequest r) {
            client.search(r, up(listener));
        } else if (request instanceof IndexRequest r) {
            client.index(r, up(listener));
        } else if (request instanceof BulkRequest r) {
            client.bulk(r, up(listener));
        } else if (request instanceof UpdateRequest r) {
            client.update(r, up(listener));
        } else if (request instanceof DeleteRequest r) {
            client.delete(r, up(listener));
        } else if (request instanceof UpdateByQueryRequest r) {
            client.execute(UpdateByQueryAction.INSTANCE, r, up(listener));
        } else if (request instanceof DeleteByQueryRequest r) {
            client.execute(DeleteByQueryAction.INSTANCE, r, up(listener));
        } else if (request instanceof CreateIndexRequest r) {
            client.admin().indices().create(r, up(listener));
        } else if (request instanceof DeleteIndexRequest r) {
            client.admin().indices().delete(r, up(listener));
        } else if (request instanceof GetMappingsRequest r) {
            client.admin().indices().getMappings(r, up(listener));
        } else if (request instanceof PutMappingRequest r) {
            client.admin().indices().putMapping(r, up(listener));
        } else {
            listener.onFailure(new IllegalStateException("nothing knows how to run a " + request.getClass().getSimpleName()));
        }
    }

    /** Responses print themselves as JSON through one of three interfaces. */
    static void write(XContentBuilder builder, ActionResponse response) throws IOException {
        if (response instanceof ChunkedToXContent chunked) {
            ChunkedToXContent.wrapAsToXContent(chunked).toXContent(builder, ToXContent.EMPTY_PARAMS);
        } else if (response instanceof ToXContentObject object) {
            object.toXContent(builder, ToXContent.EMPTY_PARAMS);
        } else { // the by-query responses are fragments
            builder.startObject();
            ((ToXContentFragment) response).toXContent(builder, ToXContent.EMPTY_PARAMS);
            builder.endObject();
        }
    }

    /** The response as a JSON string, taken while it is still ours to read. */
    static String json(ActionResponse response) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        write(builder, response);
        return Strings.toString(builder);
    }

    /** One line on how it went, for the chat history. */
    static String summary(ActionResponse response) {
        if (response instanceof SearchResponse search) {
            // the ids go in so that "make it 22" after a search knows which document "it" is
            var total = search.getHits().getTotalHits();
            StringBuilder text = new StringBuilder(total == null ? "search done" : total.value() + " hits");
            SearchHit[] hits = search.getHits().getHits();
            for (int i = 0; i < hits.length && i < 5; i++) {
                text.append(i == 0 ? ": " : ", ").append("[id ").append(hits[i].getId()).append("]");
                Map<String, Object> source = hits[i].getSourceAsMap();
                if (source != null) {
                    for (String field : new String[] { "name", "title" }) {
                        if (source.get(field) != null) {
                            text.append(" ").append(source.get(field));
                            break;
                        }
                    }
                }
            }
            return text.toString();
        }
        if (response instanceof DocWriteResponse written) {
            return "document " + written.getId() + " " + written.getResult().getLowercase();
        }
        if (response instanceof BulkResponse bulk) {
            for (var item : bulk.getItems()) {
                if (item.isFailed()) {
                    return bulk.getItems().length + " documents, some failed, for example: " + item.getFailureMessage();
                }
            }
            return bulk.getItems().length + " documents, all fine";
        }
        if (response instanceof BulkByPaginatedSearchResponse byQuery) {
            String text = byQuery.getUpdated() + " updated, " + byQuery.getDeleted() + " deleted";
            if (byQuery.getBulkFailures().isEmpty() == false) {
                text += ", " + byQuery.getBulkFailures().size() + " failed: " + byQuery.getBulkFailures().get(0).getMessage();
            }
            return text;
        }
        if (response instanceof AcknowledgedResponse ack) {
            return ack.isAcknowledged() ? "acknowledged" : "not acknowledged";
        }
        return "done";
    }

    static RestStatus status(ActionResponse response) {
        return response instanceof DocWriteResponse written ? written.status() : RestStatus.OK;
    }

    // the client wants a listener of the exact response type, ours takes any response
    private static <T extends ActionResponse> ActionListener<T> up(ActionListener<ActionResponse> listener) {
        return listener.map(response -> response);
    }

    // only these may sit beside a wrapper, so a real field called "body" is never mistaken for one
    private static final Set<String> BESIDE_A_WRAPPER = Set.of("_id", "id", "index", "_index");

    /**
     * Models put the id inside the document as "_id", and sometimes wrap the
     * document itself the way the other actions wrap theirs. Elasticsearch wants
     * the id outside and the fields bare.
     */
    @SuppressWarnings("unchecked")
    private static IndexRequest document(String index, String id, Map<String, Object> doc) {
        for (String wrapper : new String[] { "body", "doc", "source", "_source" }) {
            // only when the wrapper is the whole document, so {"title": ..., "body": ...} stays intact
            if (doc.get(wrapper) instanceof Map<?, ?> inner
                && doc.keySet().stream().allMatch(key -> key.equals(wrapper) || BESIDE_A_WRAPPER.contains(key))) {
                Map<String, Object> unwrapped = new LinkedHashMap<>((Map<String, Object>) inner);
                for (String beside : BESIDE_A_WRAPPER) {
                    if (doc.get(beside) != null && unwrapped.containsKey(beside) == false) {
                        unwrapped.put(beside, doc.get(beside));
                    }
                }
                doc = unwrapped;
                break;
            }
        }
        if (doc.containsKey("_id") || doc.containsKey("id")) {
            doc = new LinkedHashMap<>(doc);
            Object found = doc.remove("_id");
            Object other = doc.remove("id");
            id = String.valueOf(found != null ? found : other);
        }
        doc.remove("_index");
        doc.remove("index");
        return new IndexRequest(index).id(id).source(doc);
    }

    private static String index(Plan plan) {
        return need(plan.index(), "index", plan);
    }

    private static String id(Plan plan) {
        return need(plan.id(), "id", plan);
    }

    private static Map<String, Object> body(Plan plan) {
        return need(plan.body(), "body", plan);
    }

    // anything that changes an index runs against one named index, never a pattern
    private static String oneIndex(Plan plan) {
        String index = index(plan);
        if (index.contains("*") || index.contains(",") || index.equals("_all")) {
            throw new Refused("[" + plan.action() + "] needs one index name, not [" + index + "]");
        }
        return index;
    }

    private static <T> T need(T value, String what, Plan plan) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw bad("the model's plan for [" + plan.action() + "] has no \"" + what + "\"");
        }
        return value;
    }

    // the model's fault, not the caller's, so 502 like the other "model talked nonsense" errors
    private static ElasticsearchStatusException bad(String message) {
        return new ElasticsearchStatusException(message, RestStatus.BAD_GATEWAY);
    }

    @SuppressWarnings("unchecked")
    private static QueryBuilder query(Plan plan, XContentParserConfiguration parserConfig) throws IOException {
        Object query = body(plan).get("query");
        if (query instanceof Map == false) {
            throw bad("[" + plan.action() + "] needs a \"query\" in the body");
        }
        try (XContentParser parser = XContentHelper.mapToXContentParser(parserConfig, (Map<String, ?>) query)) {
            return AbstractQueryBuilder.parseTopLevelQuery(parser);
        }
    }

    /**
     * Small models get the nesting of a create index body wrong in every way
     * possible. Accept {"mappings": {"properties": {...}}}, {"mappings": {fields}},
     * {"properties": {...}} and plain {fields}, hand Elasticsearch the first form.
     */
    static Map<String, Object> indexBody(Map<String, Object> body) {
        if (body == null) {
            return Map.of();
        }
        Map<String, Object> fixed = new LinkedHashMap<>(body);
        Object mappings = fixed.get("mappings");
        if (mappings instanceof Map<?, ?> m && m.containsKey("properties") == false && looksLikeFields(m)) {
            fixed.put("mappings", Map.of("properties", m));
        } else if (mappings == null && fixed.get("properties") instanceof Map<?, ?> properties) {
            fixed.remove("properties");
            fixed.put("mappings", Map.of("properties", properties));
        } else if (mappings == null && fixed.containsKey("settings") == false && looksLikeFields(fixed)) {
            return Map.of("mappings", Map.of("properties", body));
        }
        return fixed;
    }

    /** Same story for put mapping, which wants {"properties": {...}} and nothing around it. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> mappingBody(Map<String, Object> body) {
        if (body.get("mappings") instanceof Map<?, ?> inner) {
            body = (Map<String, Object>) inner;
        }
        if (body.containsKey("properties") == false && looksLikeFields(body)) {
            return Map.of("properties", body);
        }
        return body;
    }

    private static boolean looksLikeFields(Map<?, ?> map) {
        return map.isEmpty() == false
            && map.values().stream().allMatch(v -> v instanceof Map<?, ?> field && (field.containsKey("type") || field.containsKey("properties")));
    }
}
