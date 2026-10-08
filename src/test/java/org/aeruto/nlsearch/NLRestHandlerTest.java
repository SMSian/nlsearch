package org.aeruto.nlsearch;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NLRestHandlerTest extends EsLoggingTest {

    static final Map<String, Object> MAPPINGS = Map.of(
        "products", Map.of("properties", Map.of(
            "name", Map.of("type", "text"),
            "category", Map.of("type", "keyword"),
            "price", Map.of("type", "double"))),
        "orders", Map.of("properties", Map.of(
            "status", Map.of("type", "keyword")))
    );

    static ActionRequest prepare(String modelAnswer) throws IOException {
        return NLRestHandler.prepare(Plan.parse(modelAnswer), MAPPINGS, ActionsTest.CONFIG, feature -> true);
    }

    @Test
    void aRefusalComesBeforeTheChecksSoItStaysFinal() {
        // in 0.3 the checks ran first and each of these failed there as "there is no index
        // called [...]", which is retried, and a retry is free to narrow a delete over every
        // index down to one that runs
        assertThrows(Actions.Refused.class,
            () -> prepare("{\"action\": \"delete_by_query\", \"index\": \"*\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertThrows(Actions.Refused.class,
            () -> prepare("{\"action\": \"update_by_query\", \"index\": \"_all\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertThrows(Actions.Refused.class,
            () -> prepare("{\"action\": \"delete_by_query\", \"index\": \"products,orders\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertThrows(Actions.Refused.class,
            () -> prepare("{\"action\": \"search\", \"index\": \".nlsearch-history\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertThrows(Actions.Refused.class,
            () -> prepare("{\"action\": \"delete_by_query\", \"index\": \".nlsearch-analysis\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        // a list is not checked against a mapping, so the guard is all that stands in the way
        assertThrows(Actions.Refused.class,
            () -> prepare("{\"action\": \"search\", \"index\": \"products,.nlsearch-history\", \"body\": {\"query\": {\"match_all\": {}}}}"));
    }

    @Test
    void aSearchOverSeveralIndicesIsNotCheckedAgainstOneMapping() throws IOException {
        // the prompt tells the model to write * for every index
        SearchRequest everywhere = (SearchRequest) prepare(
            "{\"action\": \"search\", \"index\": \"*\", \"body\": {\"query\": {\"term\": {\"status\": \"shipped\"}}}}");
        assertArrayEquals(new String[] { "*" }, everywhere.indices());
        assertDoesNotThrow(() -> prepare("{\"action\": \"search\", \"index\": \"products,orders\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertDoesNotThrow(() -> prepare("{\"action\": \"search\", \"index\": \"_all\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertDoesNotThrow(() -> prepare("{\"action\": \"search\", \"body\": {\"query\": {\"match_all\": {}}}}"));
    }

    @Test
    void oneNamedIndexIsStillCheckedAndWorthARetry() {
        IllegalArgumentException index = assertThrows(IllegalArgumentException.class,
            () -> prepare("{\"action\": \"search\", \"index\": \"shoes\", \"body\": {\"query\": {\"match_all\": {}}}}"));
        assertFalse(index instanceof Actions.Refused);
        assertTrue(index.getMessage().contains("there is no index called [shoes]"), index.getMessage());

        IllegalArgumentException field = assertThrows(IllegalArgumentException.class,
            () -> prepare("{\"action\": \"search\", \"index\": \"products\", \"body\": {\"query\": {\"term\": {\"colour\": \"red\"}}}}"));
        assertFalse(field instanceof Actions.Refused);
        assertTrue(field.getMessage().contains("has no field colour"), field.getMessage());

        assertDoesNotThrow(() -> prepare("{\"action\": \"search\", \"index\": \"products\", \"body\": {\"query\": {\"term\": {\"category\": \"shoes\"}}}}"));
    }

    @Test
    void theEmptyResultHintOnlyLooksAtOneNamedIndex() {
        Map<String, Object> facts = Map.of("products", Map.of("documents", 3, "values", Map.of("category", List.of("shoes", "kitchen"))));
        String hint = NLRestHandler.searchedTheWrongField(
            Plan.parse("{\"action\": \"search\", \"index\": \"products\", \"body\": {\"query\": {\"match\": {\"name\": \"shoes\"}}}}"), facts);
        assertTrue(hint != null && hint.contains("keyword field [category]"), String.valueOf(hint));

        // no index, or a pattern, has nothing to look up; and trimmed facts are an empty Map.of(), which throws on get(null)
        Plan everywhere = Plan.parse("{\"action\": \"search\", \"body\": {\"query\": {\"match\": {\"name\": \"shoes\"}}}}");
        assertNull(NLRestHandler.searchedTheWrongField(everywhere, Map.of()));
        assertNull(NLRestHandler.searchedTheWrongField(everywhere, facts));
        assertNull(NLRestHandler.searchedTheWrongField(
            Plan.parse("{\"action\": \"search\", \"index\": \"*\", \"body\": {\"query\": {\"match\": {\"name\": \"shoes\"}}}}"), facts));
    }
}
