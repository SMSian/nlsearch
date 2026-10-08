package org.aeruto.nlsearch;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.rest.RestStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanTest extends EsLoggingTest {

    @Test
    void parsesAPlainAnswer() {
        Plan plan = Plan.parse("{\"action\": \"search\", \"index\": \"products\", \"body\": {\"query\": {\"match_all\": {}}}}");
        assertEquals("search", plan.action());
        assertEquals("products", plan.index());
        assertEquals(Map.of("query", Map.of("match_all", Map.of())), plan.body());
        assertNull(plan.id());
        assertNull(plan.docs());
        assertNull(plan.text());
    }

    @Test
    void digsTheJsonOutOfFencesAndChatter() {
        Plan plan = Plan.parse("Sure! Here you go:\n```json\n{\"action\": \"delete\", \"index\": \"products\", \"id\": \"7\"}\n```\nAnything else?");
        assertEquals("delete", plan.action());
        assertEquals("products", plan.index());
        assertEquals("7", plan.id());
    }

    @Test
    void numericIdsBecomeText() {
        Plan plan = Plan.parse("{\"action\": \"update\", \"index\": \"products\", \"id\": 42, \"body\": {\"price\": 20}}");
        assertEquals("42", plan.id());
        assertEquals(Map.of("price", 20), plan.body());
    }

    @Test
    void keepsTheDocsOfABulk() {
        Plan plan = Plan.parse("{\"action\": \"bulk\", \"index\": \"products\", \"docs\": [{\"name\": \"a\"}, {\"name\": \"b\"}]}");
        assertEquals(List.of(Map.of("name", "a"), Map.of("name", "b")), plan.docs());
    }

    @Test
    void keepsTheTextOfAReply() {
        Plan plan = Plan.parse("{\"action\": \"reply\", \"text\": \"Which index do you mean?\"}");
        assertEquals("reply", plan.action());
        assertEquals("Which index do you mean?", plan.text());
    }

    @Test
    void complainsWhenThereIsNoJsonAtAll() {
        ElasticsearchStatusException e = assertThrows(ElasticsearchStatusException.class, () -> Plan.parse("I cannot do that."));
        assertEquals(RestStatus.BAD_GATEWAY, e.status());
        assertTrue(e.getMessage().contains("I cannot do that."), e.getMessage());
    }

    @Test
    void complainsWhenTheJsonIsBroken() {
        assertThrows(ElasticsearchStatusException.class, () -> Plan.parse("{\"action\": \"search\", oops}"));
    }

    @Test
    void complainsWhenTheActionIsMissing() {
        ElasticsearchStatusException e = assertThrows(ElasticsearchStatusException.class, () -> Plan.parse("{\"index\": \"products\"}"));
        assertTrue(e.getMessage().contains("action"), e.getMessage());
    }

    @Test
    void complainsAboutAnEmptyAnswer() {
        assertThrows(ElasticsearchStatusException.class, () -> Plan.parse(""));
        assertThrows(ElasticsearchStatusException.class, () -> Plan.parse(null));
    }

    @Test
    void turnsBackIntoAMapWithoutTheEmptyParts() {
        Plan plan = Plan.parse("{\"action\": \"index\", \"index\": \"products\", \"body\": {\"name\": \"Mug\"}}");
        assertEquals(Map.of("action", "index", "index", "products", "body", Map.of("name", "Mug")), plan.toMap());
    }
}
