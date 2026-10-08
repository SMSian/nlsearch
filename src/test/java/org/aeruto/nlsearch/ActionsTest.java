package org.aeruto.nlsearch;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.GetMappingsResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.cluster.metadata.MappingMetadata;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.index.reindex.UpdateByQueryRequest;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.xcontent.DeprecationHandler;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsTest extends EsLoggingTest {

    // the same registry of query and aggregation parsers a real node hands to rest handlers
    static final XContentParserConfiguration CONFIG = XContentParserConfiguration.EMPTY
        .withRegistry(new NamedXContentRegistry(new SearchModule(Settings.EMPTY, List.of()).getNamedXContents()))
        .withDeprecationHandler(DeprecationHandler.IGNORE_DEPRECATIONS);

    /** PutMappingRequest.source() is a String in 9.5.4 and a BytesReference in 9.5.5. */
    static String mappingSource(PutMappingRequest request) {
        Object source = request.source();
        return source instanceof org.elasticsearch.common.bytes.BytesReference bytes ? bytes.utf8ToString() : String.valueOf(source);
    }

    static ActionRequest build(String modelAnswer) throws IOException {
        return Actions.toRequest(Plan.parse(modelAnswer), CONFIG, feature -> true);
    }

    @Test
    void search() throws IOException {
        SearchRequest request = (SearchRequest) build(
            "{\"action\": \"search\", \"index\": \"products\", \"body\": {\"size\": 3, \"query\": {\"match\": {\"name\": \"mug\"}}, \"sort\": [{\"price\": \"asc\"}]}}"
        );
        assertArrayEquals(new String[] { "products" }, request.indices());
        assertEquals(3, request.source().size());
        assertInstanceOf(MatchQueryBuilder.class, request.source().query());
        assertEquals(1, request.source().sorts().size());
    }

    @Test
    void searchWithoutAnIndexLooksEverywhere() throws IOException {
        SearchRequest request = (SearchRequest) build("{\"action\": \"search\", \"body\": {\"query\": {\"match_all\": {}}}}");
        assertArrayEquals(new String[] { "*" }, request.indices());
    }

    @Test
    void searchWithAggregations() throws IOException {
        SearchRequest request = (SearchRequest) build(
            "{\"action\": \"search\", \"index\": \"products\", \"body\": {\"size\": 0, \"aggs\": {\"per_category\": {\"terms\": {\"field\": \"category\"}}}}}"
        );
        assertEquals(0, request.source().size());
        assertEquals(1, request.source().aggregations().count());
    }

    @Test
    void aBadQueryIsRejectedBeforeItReachesTheShards() {
        assertThrows(Exception.class, () -> build("{\"action\": \"search\", \"index\": \"products\", \"body\": {\"query\": {\"no_such_query\": {}}}}"));
    }

    @Test
    void indexADocument() throws IOException {
        IndexRequest request = (IndexRequest) build("{\"action\": \"index\", \"index\": \"products\", \"id\": \"1\", \"body\": {\"name\": \"Mug\", \"price\": 12.5}}");
        assertEquals("products", request.index());
        assertEquals("1", request.id());
        assertEquals(Map.of("name", "Mug", "price", 12.5), request.sourceAsMap());
        assertEquals(RefreshPolicy.IMMEDIATE, request.getRefreshPolicy());
    }

    @Test
    void anIdInsideTheDocumentMovesOutside() throws IOException {
        IndexRequest request = (IndexRequest) build("{\"action\": \"index\", \"index\": \"products\", \"body\": {\"_id\": 7, \"name\": \"Mug\"}}");
        assertEquals("7", request.id());
        assertEquals(Map.of("name", "Mug"), request.sourceAsMap());

        BulkRequest bulk = (BulkRequest) build("{\"action\": \"bulk\", \"index\": \"products\", \"docs\": [{\"_id\": \"a\", \"name\": \"a\"}, {\"name\": \"b\"}]}");
        assertEquals("a", ((IndexRequest) bulk.requests().get(0)).id());
        assertEquals(Map.of("name", "a"), ((IndexRequest) bulk.requests().get(0)).sourceAsMap());
        assertNull(((IndexRequest) bulk.requests().get(1)).id());
    }

    @Test
    void indexWithoutAnIdLetsElasticsearchPickOne() throws IOException {
        IndexRequest request = (IndexRequest) build("{\"action\": \"index\", \"index\": \"products\", \"body\": {\"name\": \"Mug\"}}");
        assertNull(request.id());
    }

    @Test
    void aWrappedDocumentIsUnwrapped() throws IOException {
        IndexRequest one = (IndexRequest) build("{\"action\": \"index\", \"index\": \"products\", \"body\": {\"doc\": {\"name\": \"Mug\"}}}");
        assertEquals(Map.of("name", "Mug"), one.sourceAsMap());

        BulkRequest bulk = (BulkRequest) build(
            "{\"action\": \"bulk\", \"index\": \"products\", \"docs\": [{\"_id\": \"2\", \"body\": {\"name\": \"Mug\"}}, {\"name\": \"Pan\"}]}");
        assertEquals(2, bulk.numberOfActions());
        IndexRequest first = (IndexRequest) bulk.requests().get(0);
        assertEquals("2", first.id());
        assertEquals(Map.of("name", "Mug"), first.sourceAsMap());
        assertEquals(Map.of("name", "Pan"), ((IndexRequest) bulk.requests().get(1)).sourceAsMap());
    }

    @Test
    void aRealFieldCalledBodyIsLeftAlone() throws IOException {
        IndexRequest request = (IndexRequest) build(
            "{\"action\": \"index\", \"index\": \"posts\", \"body\": {\"title\": \"Hello\", \"body\": {\"text\": \"a nested value\"}}}");
        assertEquals(Map.of("title", "Hello", "body", Map.of("text", "a nested value")), request.sourceAsMap());
    }

    @Test
    void bulk() throws IOException {
        BulkRequest request = (BulkRequest) build("{\"action\": \"bulk\", \"index\": \"products\", \"docs\": [{\"name\": \"a\"}, {\"name\": \"b\"}]}");
        assertEquals(2, request.numberOfActions());
        assertEquals(RefreshPolicy.IMMEDIATE, request.getRefreshPolicy());
        IndexRequest second = (IndexRequest) request.requests().get(1);
        assertEquals("products", second.index());
        assertEquals(Map.of("name", "b"), second.sourceAsMap());
    }

    @Test
    void update() throws IOException {
        UpdateRequest request = (UpdateRequest) build("{\"action\": \"update\", \"index\": \"products\", \"id\": \"1\", \"body\": {\"price\": 20}}");
        assertEquals("products", request.index());
        assertEquals("1", request.id());
        assertEquals(Map.of("price", 20), request.doc().sourceAsMap());
        assertEquals(RefreshPolicy.IMMEDIATE, request.getRefreshPolicy());
    }

    @Test
    void delete() throws IOException {
        DeleteRequest request = (DeleteRequest) build("{\"action\": \"delete\", \"index\": \"products\", \"id\": \"1\"}");
        assertEquals("products", request.index());
        assertEquals("1", request.id());
        assertEquals(RefreshPolicy.IMMEDIATE, request.getRefreshPolicy());
    }

    @Test
    void updateByQuery() throws IOException {
        UpdateByQueryRequest request = (UpdateByQueryRequest) build(
            "{\"action\": \"update_by_query\", \"index\": \"products\", \"body\": {\"query\": {\"term\": {\"category\": \"kitchen\"}}, \"script\": {\"source\": \"ctx._source.price = ctx._source.price * 0.9\", \"params\": {}}}}"
        );
        assertArrayEquals(new String[] { "products" }, request.indices());
        assertInstanceOf(TermQueryBuilder.class, request.getSearchRequest().source().query());
        assertEquals("ctx._source.price = ctx._source.price * 0.9", request.getScript().getIdOrCode());
        assertTrue(request.isRefresh());
    }

    @Test
    void updateByQueryWithoutAScriptIsStillValid() throws IOException {
        UpdateByQueryRequest request = (UpdateByQueryRequest) build(
            "{\"action\": \"update_by_query\", \"index\": \"products\", \"body\": {\"query\": {\"match_all\": {}}}}"
        );
        assertNull(request.getScript());
    }

    @Test
    void deleteByQuery() throws IOException {
        DeleteByQueryRequest request = (DeleteByQueryRequest) build(
            "{\"action\": \"delete_by_query\", \"index\": \"products\", \"body\": {\"query\": {\"range\": {\"price\": {\"gt\": 100}}}}}"
        );
        assertArrayEquals(new String[] { "products" }, request.indices());
        assertInstanceOf(RangeQueryBuilder.class, request.getSearchRequest().source().query());
        assertTrue(request.isRefresh());
    }

    @Test
    void byQueryActionsNeedAQuery() {
        ElasticsearchStatusException e = assertThrows(ElasticsearchStatusException.class,
            () -> build("{\"action\": \"delete_by_query\", \"index\": \"products\", \"body\": {}}"));
        assertTrue(e.getMessage().contains("query"), e.getMessage());
        assertEquals(RestStatus.BAD_GATEWAY, e.status());
    }

    @Test
    void updateWrittenLikeTheRealApiStillWorks() throws IOException {
        UpdateRequest wrapped = (UpdateRequest) build("{\"action\": \"update\", \"index\": \"products\", \"id\": \"1\", \"body\": {\"doc\": {\"price\": 14}}}");
        assertEquals(Map.of("price", 14), wrapped.doc().sourceAsMap());

        UpdateRequest scripted = (UpdateRequest) build("{\"action\": \"update\", \"index\": \"products\", \"id\": \"1\", \"body\": {\"script\": {\"source\": \"ctx._source.price += 1\"}}}");
        assertNull(scripted.doc());
        assertEquals("ctx._source.price += 1", scripted.script().getIdOrCode());
    }

    @Test
    void bulkDocsHaveToBeDocuments() {
        assertThrows(ElasticsearchStatusException.class, () -> build("{\"action\": \"bulk\", \"index\": \"products\", \"docs\": [\"not a document\"]}"));
    }

    @Test
    void searchOverSeveralIndices() throws IOException {
        SearchRequest request = (SearchRequest) build("{\"action\": \"search\", \"index\": \"products,customers\", \"body\": {\"query\": {\"match_all\": {}}}}");
        assertArrayEquals(new String[] { "products", "customers" }, request.indices());
    }

    @Test
    void onlyReadsAreAllowedOnAGet() {
        assertTrue(Actions.READ_ONLY.contains("search"));
        assertTrue(Actions.READ_ONLY.contains("reply"));
        assertFalse(Actions.READ_ONLY.contains("delete_by_query"));
        assertFalse(Actions.READ_ONLY.contains("index"));
    }

    @Test
    void createIndex() throws IOException {
        CreateIndexRequest request = (CreateIndexRequest) build(
            "{\"action\": \"create_index\", \"index\": \"posts\", \"body\": {\"settings\": {\"number_of_shards\": 1}, \"mappings\": {\"properties\": {\"title\": {\"type\": \"text\"}}}}}"
        );
        assertEquals("posts", request.index());
        assertEquals("1", request.settings().get("number_of_shards"));
        assertEquals("{\"_doc\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}", request.mappings());
    }

    @Test
    void createIndexForgivesAMissingMappingsWrapper() throws IOException {
        CreateIndexRequest request = (CreateIndexRequest) build(
            "{\"action\": \"create_index\", \"index\": \"posts\", \"body\": {\"properties\": {\"title\": {\"type\": \"text\"}}}}"
        );
        assertTrue(request.mappings().contains("\"title\""), request.mappings());
    }

    @Test
    void createIndexForgivesFieldsStraightUnderMappingsOrAtTheTop() throws IOException {
        CreateIndexRequest underMappings = (CreateIndexRequest) build(
            "{\"action\": \"create_index\", \"index\": \"posts\", \"body\": {\"mappings\": {\"title\": {\"type\": \"text\"}, \"tags\": {\"type\": \"keyword\"}}}}"
        );
        assertTrue(underMappings.mappings().contains("\"properties\""), underMappings.mappings());
        assertTrue(underMappings.mappings().contains("\"tags\""), underMappings.mappings());

        CreateIndexRequest atTheTop = (CreateIndexRequest) build(
            "{\"action\": \"create_index\", \"index\": \"posts\", \"body\": {\"title\": {\"type\": \"text\"}, \"author\": {\"type\": \"keyword\"}}}"
        );
        assertTrue(atTheTop.mappings().contains("\"author\""), atTheTop.mappings());
        assertEquals(0, atTheTop.settings().size());
    }

    @Test
    void createIndexWithNoBodyAtAllIsFine() throws IOException {
        CreateIndexRequest request = (CreateIndexRequest) build("{\"action\": \"create_index\", \"index\": \"posts\"}");
        assertEquals("posts", request.index());
    }

    @Test
    void putMapping() throws IOException {
        PutMappingRequest request = (PutMappingRequest) build(
            "{\"action\": \"put_mapping\", \"index\": \"products\", \"body\": {\"properties\": {\"tags\": {\"type\": \"keyword\"}}}}"
        );
        assertArrayEquals(new String[] { "products" }, request.indices());
        assertTrue(mappingSource(request).contains("\"tags\""), mappingSource(request));
    }

    @Test
    void putMappingForgivesAnExtraMappingsWrapper() throws IOException {
        PutMappingRequest request = (PutMappingRequest) build(
            "{\"action\": \"put_mapping\", \"index\": \"products\", \"body\": {\"mappings\": {\"properties\": {\"tags\": {\"type\": \"keyword\"}}}}}"
        );
        assertTrue(mappingSource(request).contains("\"tags\""), mappingSource(request));
        assertFalse(mappingSource(request).contains("\"mappings\""), mappingSource(request));
    }

    @Test
    void putMappingForgivesBareFields() throws IOException {
        PutMappingRequest request = (PutMappingRequest) build(
            "{\"action\": \"put_mapping\", \"index\": \"products\", \"body\": {\"brand\": {\"type\": \"keyword\"}}}"
        );
        assertTrue(mappingSource(request).startsWith("{\"properties\":{\"brand\""), mappingSource(request));
    }

    @Test
    void getMappingAndListIndices() throws IOException {
        GetMappingsRequest one = (GetMappingsRequest) build("{\"action\": \"get_mapping\", \"index\": \"products\"}");
        assertArrayEquals(new String[] { "products" }, one.indices());
        GetMappingsRequest all = (GetMappingsRequest) build("{\"action\": \"list_indices\"}");
        assertArrayEquals(new String[] { "*" }, all.indices());
    }

    @Test
    void theHistoryIndexIsOffLimits() {
        Actions.Refused e = assertThrows(Actions.Refused.class,
            () -> build("{\"action\": \"delete_index\", \"index\": \".nlsearch-history\"}"));
        assertTrue(e.getMessage().contains(".nlsearch-history"), e.getMessage());
        assertThrows(Actions.Refused.class, () -> build("{\"action\": \"search\", \"index\": \".nlsearch-history\", \"body\": {}}"));
    }

    @Test
    void theAnalysisIndexIsOffLimitsToo() {
        Actions.Refused e = assertThrows(Actions.Refused.class,
            () -> build("{\"action\": \"delete_index\", \"index\": \".nlsearch-analysis\"}"));
        assertTrue(e.getMessage().contains(".nlsearch-analysis"), e.getMessage());
        // the refusal says what to do instead, because wanting it rebuilt is a fair thing to want
        assertTrue(e.getMessage().contains("/_nl/analyze"), e.getMessage());
        assertThrows(Actions.Refused.class, () -> build("{\"action\": \"search\", \"index\": \".nlsearch-analysis\", \"body\": {}}"));
    }

    @Test
    void deleteIndex() throws IOException {
        DeleteIndexRequest request = (DeleteIndexRequest) build("{\"action\": \"delete_index\", \"index\": \"products\"}");
        assertArrayEquals(new String[] { "products" }, request.indices());
    }

    @Test
    void changesRefusePatterns() {
        assertThrows(Actions.Refused.class, () -> build("{\"action\": \"delete_index\", \"index\": \"*\"}"));
        assertThrows(Actions.Refused.class, () -> build("{\"action\": \"delete_index\", \"index\": \"_all\"}"));
        assertThrows(Actions.Refused.class, () -> build("{\"action\": \"delete_by_query\", \"index\": \"a,b\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertThrows(Actions.Refused.class, () -> build("{\"action\": \"update_by_query\", \"index\": \"logs-*\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertThrows(Actions.Refused.class, () -> build("{\"action\": \"put_mapping\", \"index\": \"*\", \"body\": {\"properties\": {}}}"));
    }

    @Test
    void missingPiecesAreNamedInTheErrorAndBlamedOnTheModel() {
        ElasticsearchStatusException noId = assertThrows(ElasticsearchStatusException.class, () -> build("{\"action\": \"delete\", \"index\": \"products\"}"));
        assertTrue(noId.getMessage().contains("\"id\""), noId.getMessage());
        assertEquals(RestStatus.BAD_GATEWAY, noId.status());
        ElasticsearchStatusException noBody = assertThrows(ElasticsearchStatusException.class, () -> build("{\"action\": \"index\", \"index\": \"products\"}"));
        assertTrue(noBody.getMessage().contains("\"body\""), noBody.getMessage());
        ElasticsearchStatusException noIndex = assertThrows(ElasticsearchStatusException.class, () -> build("{\"action\": \"index\", \"body\": {\"a\": 1}}"));
        assertTrue(noIndex.getMessage().contains("\"index\""), noIndex.getMessage());
        ElasticsearchStatusException unknown = assertThrows(ElasticsearchStatusException.class, () -> build("{\"action\": \"teleport\"}"));
        assertTrue(unknown.getMessage().contains("teleport"), unknown.getMessage());
    }

    @Test
    void statusIs201ForACreatedDocumentAnd200Otherwise() {
        IndexResponse created = new IndexResponse(new ShardId("products", "uuid", 0), "1", 0, 1, 1, true);
        assertEquals(RestStatus.CREATED, Actions.status(created));
        assertEquals(RestStatus.OK, Actions.status(AcknowledgedResponse.TRUE));
    }

    @Test
    void responsesAreWrittenAsJson() throws IOException {
        XContentBuilder plain = XContentFactory.jsonBuilder();
        Actions.write(plain, AcknowledgedResponse.TRUE);
        assertEquals("{\"acknowledged\":true}", Strings.toString(plain));

        XContentBuilder chunked = XContentFactory.jsonBuilder();
        Actions.write(chunked, new GetMappingsResponse(Map.of("products", MappingMetadata.EMPTY_MAPPINGS)));
        String json = Strings.toString(chunked);
        assertTrue(json.startsWith("{\"products\":{\"mappings\":"), json);
    }

    @Test
    void summariesSayWhatHappened() {
        assertEquals("acknowledged", Actions.summary(AcknowledgedResponse.TRUE));
        IndexResponse created = new IndexResponse(new ShardId("products", "uuid", 0), "1", 0, 1, 1, true);
        assertEquals("document 1 created", Actions.summary(created));
    }

    @Test
    void aHitIsNamedWithoutLookingLikeAField() {
        // "[id 1] Red Running Shoe" once had the model write {"term": {"id": 1}}
        // against an index with no id field: valid query, zero results, no error
        assertEquals("Red Running Shoe (document 1)",
                     Actions.describe("1", Map.of("name", "Red Running Shoe")));
        assertEquals("Annual Report (document 7)",
                     Actions.describe("7", Map.of("title", "Annual Report")));
        assertEquals("document 3", Actions.describe("3", Map.of("sku", "SKU-1")));
        assertEquals("document 3", Actions.describe("3", null));
    }

    @Test
    void theReasonGivenToTheModelCarriesTheCauseNotJustTheWrapper() {
        Exception root = new IllegalArgumentException("[term] query does not support [gt]");
        Exception wrapper = new IllegalStateException("[1:87] [bool] failed to parse field [filter]", root);
        assertEquals("[1:87] [bool] failed to parse field [filter]: [term] query does not support [gt]",
                     Actions.why(wrapper));
    }

    @Test
    void aReasonWithNothingUnderneathIsLeftAlone() {
        assertEquals("plain", Actions.why(new IllegalArgumentException("plain")));
    }

    @Test
    void aRepeatedReasonIsNotSaidTwice() {
        Exception root = new IllegalArgumentException("same words");
        assertEquals("same words", Actions.why(new IllegalStateException("same words", root)));
    }

    @Test
    void theFieldsAQueryUsesAreFound() {
        Map<String, Object> body = Map.of(
            "query", Map.of("bool", Map.of(
                "must", List.of(Map.of("match", Map.of("name", "red"))),
                "filter", List.of(Map.of("term", Map.of("category", "shoes")),
                                  Map.of("range", Map.of("price", Map.of("lt", 50)))),
                "must_not", List.of(Map.of("exists", Map.of("field", "discontinued"))))),
            "sort", List.of(Map.of("added", "desc")),
            "aggs", Map.of("per_brand", Map.of("terms", Map.of("field", "brand"))));

        assertEquals(Set.of("name", "category", "price", "discontinued", "added", "brand"),
                     Actions.fieldsUsed(body));
    }

    @Test
    void aggregationsInsideAggregationsAreFoundToo() {
        Map<String, Object> body = Map.of("aggs", Map.of(
            "per_category", Map.of("terms", Map.of("field", "category"),
                                   "aggs", Map.of("average", Map.of("avg", Map.of("field", "price"))))));
        assertEquals(Set.of("category", "price"), Actions.fieldsUsed(body));
    }

    @Test
    void aShapeWeDoNotRecogniseIsIgnoredRatherThanGuessedAt() {
        // this feeds a check that refuses a plan, so a false positive would reject
        // a query that would have worked. Unknown shapes contribute nothing.
        assertEquals(Set.of(), Actions.fieldsUsed(Map.of("query", Map.of("match_all", Map.of()))));
        assertEquals(Set.of(), Actions.fieldsUsed(Map.of("query", Map.of("ids", Map.of("values", List.of("1"))))));
        assertEquals(Set.of(), Actions.fieldsUsed(null));
    }

    @Test
    void theWordsEachMatchClauseIsLookingForAreFound() {
        Map<String, Object> body = Map.of("query", Map.of("bool", Map.of(
            "must", List.of(Map.of("match", Map.of("name", Map.of("query", "red shoes", "operator", "and")))),
            "should", List.of(Map.of("match_phrase", Map.of("description", "induction hob"))))));
        Map<String, String> looking = Actions.matched(body);
        assertEquals("red shoes", looking.get("name"));
        assertEquals("induction hob", looking.get("description"));
    }

    @Test
    void aQueryWithNoMatchClauseIsLookingForNothing() {
        assertEquals(Map.of(), Actions.matched(Map.of("query", Map.of("term", Map.of("category", "shoes")))));
        assertEquals(Map.of(), Actions.matched(null));
    }

    @Test
    void aQueryThatRequiresAndExcludesTheSameThingIsCaught() {
        Map<String, Object> body = Map.of("query", Map.of("bool", Map.of(
            "must", List.of(Map.of("term", Map.of("category", "electronics")),
                            Map.of("term", Map.of("brand", "Pixel"))),
            "must_not", List.of(Map.of("term", Map.of("brand", "Pixel"))))));
        assertEquals("brand Pixel", Actions.contradiction(body));
    }

    @Test
    void anOrdinaryExclusionIsNotAContradiction() {
        Map<String, Object> body = Map.of("query", Map.of("bool", Map.of(
            "must", List.of(Map.of("term", Map.of("category", "electronics"))),
            "must_not", List.of(Map.of("term", Map.of("brand", "Pixel"))))));
        assertNull(Actions.contradiction(body));
        assertNull(Actions.contradiction(Map.of("query", Map.of("match_all", Map.of()))));
        assertNull(Actions.contradiction(null));
    }

    @Test
    void theNewRulesNameNoFieldFromAnyParticularDataset() {
        // the rules have to work on an index nobody has seen. Field names appear only
        // inside the block that is explicitly introduced as an imaginary example.
        String rules = Planner.INSTRUCTIONS.substring(Planner.INSTRUCTIONS.indexOf("Rules."),
                                                      Planner.INSTRUCTIONS.indexOf("The mistakes that get made"));
        for (String borrowed : new String[] { "in_stock", "stock_count", "signed_up", "lifetime_value", "vip" }) {
            assertFalse(rules.contains(borrowed), "the rules still mention " + borrowed);
        }
    }
}
