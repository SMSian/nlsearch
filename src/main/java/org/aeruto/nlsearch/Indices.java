package org.aeruto.nlsearch;

import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.search.MultiSearchRequest;
import org.elasticsearch.action.search.MultiSearchResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.aggregations.metrics.NumericMetricsAggregation;
import org.elasticsearch.search.aggregations.metrics.TopHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything we ask Elasticsearch about itself, so that the model has something
 * to reason from. Nothing here talks to a model; it only looks at the cluster.
 */
final class Indices {

    /** A keyword field with more values than this is an id or a name, not a set worth listing. */
    static final int MAX_VALUES = 40;

    /** How much of an index is shown while working out what it means. */
    static final int VALUES_TO_ILLUSTRATE = 15;
    static final int EXAMPLES_PER_VALUE = 2;
    static final int EXAMPLES_UNFILTERED = 5;
    static final int MAX_EXAMPLES = 40;

    private static final Set<String> SPANNED = Set.of(
        "long", "integer", "short", "byte", "double", "float", "half_float", "scaled_float", "date");

    private Indices() {}

    /** Index name to mapping. The plugin's own hidden indices are not among them. */
    static Map<String, Object> mappings(NodeClient client) {
        GetMappingsRequest request = new GetMappingsRequest(Actions.MASTER_TIMEOUT)
            .indices("*")
            .indicesOptions(IndicesOptions.lenientExpandOpen());
        Map<String, Object> mappings = new TreeMap<>();
        client.admin().indices().getMappings(request).actionGet().mappings()
            .forEach((name, mapping) -> mappings.put(name, mapping.sourceAsMap()));
        return mappings;
    }

    /**
     * What is actually in each index: how many documents, every value the small
     * keyword fields hold, and the span of the numbers and dates. Without this the
     * model writes {"match": {"name": "kitchen"}} where the data wants
     * {"term": {"category": "kitchen"}}, or filters on a year the data never covers.
     */
    static Map<String, Object> facts(NodeClient client, Map<String, Object> mappings) {
        List<String> indices = new ArrayList<>();
        List<Map<String, String>> fieldsPerIndex = new ArrayList<>();
        MultiSearchRequest msearch = new MultiSearchRequest();
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            Map<String, String> fields = summarisable(entry.getValue());
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
                facts.put(indices.get(i), about(items[i].getResponse(), fieldsPerIndex.get(i)));
            }
        } finally {
            response.decRef();
        }
        return facts;
    }

    private static Map<String, Object> about(SearchResponse found, Map<String, String> fields) {
        Map<String, Object> about = new LinkedHashMap<>();
        var total = found.getHits().getTotalHits();
        if (total != null) {
            about.put("documents", total.value());
        }
        InternalAggregations aggregations = found.getAggregations();
        if (aggregations == null) {
            return about;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> spans = new LinkedHashMap<>();
        fields.forEach((name, type) -> {
            if (type.equals("keyword")) {
                if (aggregations.get("v_" + name) instanceof Terms terms) {
                    List<String> list = terms.getBuckets().stream().map(Terms.Bucket::getKeyAsString).toList();
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
        });
        if (values.isEmpty() == false) {
            about.put("values", values);
        }
        if (spans.isEmpty() == false) {
            about.put("from lowest to highest", spans);
        }
        return about;
    }

    /** A min or max as the field itself would show it, so a date reads as a date. */
    private static String single(InternalAggregations aggregations, String name) {
        InternalAggregation aggregation = aggregations.get(name);
        if (aggregation instanceof NumericMetricsAggregation.SingleValue value) {
            // a field with no values comes back as an infinity, and asking such an
            // aggregation to format itself throws rather than returning anything useful
            double raw = value.value();
            if (Double.isNaN(raw) || Double.isInfinite(raw)) {
                return null;
            }
            return value.getValueAsString();
        }
        return null;
    }

    /**
     * A couple of real documents for every value of every small keyword field.
     * This is what lets anything work out that dept FW means footwear: it sees the
     * shoes and boots carrying that value instead of two bare letters.
     */
    static List<Map<String, Object>> examples(NodeClient client, String index, Object mapping) {
        Map<String, String> fields = summarisable(mapping);
        SearchSourceBuilder source = new SearchSourceBuilder().size(EXAMPLES_UNFILTERED);
        fields.forEach((field, type) -> {
            if (type.equals("keyword")) {
                source.aggregation(AggregationBuilders.terms("by_" + field).field(field).size(VALUES_TO_ILLUSTRATE)
                    .subAggregation(AggregationBuilders.topHits("eg").size(EXAMPLES_PER_VALUE)));
            }
        });

        List<Map<String, Object>> examples = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        SearchResponse response = client.search(new SearchRequest(index).source(source)).actionGet();
        try {
            for (SearchHit hit : response.getHits().getHits()) {
                keep(examples, seen, hit);
            }
            InternalAggregations aggregations = response.getAggregations();
            if (aggregations != null) {
                for (String field : fields.keySet()) {
                    if (aggregations.get("by_" + field) instanceof Terms terms) {
                        for (Terms.Bucket bucket : terms.getBuckets()) {
                            if (bucket.getAggregations().get("eg") instanceof TopHits top) {
                                for (SearchHit hit : top.getHits().getHits()) {
                                    keep(examples, seen, hit);
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            response.decRef();
        }
        return examples;
    }

    @SuppressWarnings("unchecked")
    private static void keep(List<Map<String, Object>> examples, Set<String> seen, SearchHit hit) {
        if (hit.getSourceAsMap() == null || examples.size() >= MAX_EXAMPLES || seen.add(hit.getId()) == false) {
            return;
        }
        if (shorten(hit.getSourceAsMap()) instanceof Map<?, ?> trimmed) {
            examples.add((Map<String, Object>) trimmed);
        }
    }

    /** One document from each index, for when nothing has been worked out about them yet. */
    static Map<String, Object> samples(NodeClient client, Iterable<String> indices) {
        List<String> names = new ArrayList<>();
        MultiSearchRequest msearch = new MultiSearchRequest();
        for (String name : indices) {
            msearch.add(new SearchRequest(name).source(new SearchSourceBuilder().size(1)));
            names.add(name);
        }
        if (names.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> samples = new LinkedHashMap<>();
        MultiSearchResponse response = client.multiSearch(msearch).actionGet();
        try {
            MultiSearchResponse.Item[] items = response.getResponses();
            for (int i = 0; i < items.length && i < names.size(); i++) {
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

    /** How many documents facts() counted for an index, or -1 when it could not say. */
    static long documents(Object about) {
        if (about instanceof Map<?, ?> map && map.get("documents") instanceof Number count) {
            return count.longValue();
        }
        return -1;   // unknown is not empty: analyse it and find out
    }

    /**
     * Every field name a query may legally mention for this index, including the
     * sub-fields a text field declares, so "name.keyword" is as valid as "name".
     *
     * Used to catch a plan that filters on something the index does not have before
     * it is run. Elasticsearch does not complain about such a query: it matches
     * nothing and returns a perfectly good empty result, which is the one failure
     * nobody notices.
     */
    static Set<String> fields(Object mapping) {
        Set<String> names = new LinkedHashSet<>();
        collect(mapping, "", names);
        return names;
    }

    private static void collect(Object mapping, String prefix, Set<String> into) {
        if (mapping instanceof Map<?, ?> map && map.get("properties") instanceof Map<?, ?> properties) {
            properties.forEach((name, definition) -> {
                String path = prefix.isEmpty() ? String.valueOf(name) : prefix + "." + name;
                into.add(path);
                if (definition instanceof Map<?, ?> field) {
                    // a text field can declare sub-fields; an object field nests properties
                    if (field.get("fields") instanceof Map<?, ?> subs) {
                        subs.keySet().forEach(sub -> into.add(path + "." + sub));
                    }
                    collect(field, path, into);
                }
            });
        }
    }

    /** Field name to type, for the fields worth summarising. */
    static Map<String, String> summarisable(Object mapping) {
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

    /** Long texts and big arrays only cost tokens. */
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
            return list.stream().limit(5).map(Indices::shorten).toList();
        }
        return value;
    }
}
