package org.aeruto.nlsearch;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndicesTest {

    @Test
    void longTextsAreCut() {
        String text = "x".repeat(200);
        assertEquals("x".repeat(80) + "...", Indices.shorten(text));
        assertEquals("short", Indices.shorten("short"));
    }

    @Test
    void bigListsAreCutAndNestedValuesToo() {
        Object shortened = Indices.shorten(Map.of("tags", List.of(1, 2, 3, 4, 5, 6, 7), "nested", Map.of("text", "y".repeat(100))));
        assertEquals(Map.of("tags", List.of(1, 2, 3, 4, 5), "nested", Map.of("text", "y".repeat(80) + "...")), shortened);
    }

    @Test
    void theFieldsWorthSummarisingAreFoundInAMapping() {
        var fields = Indices.summarisable(Map.of("properties", Map.of(
            "title", Map.of("type", "text"),
            "dept", Map.of("type", "keyword"),
            "unit_cost", Map.of("type", "float"),
            "rcvd", Map.of("type", "date"),
            "blob", Map.of("type", "binary"))));
        assertEquals(Map.of("dept", "keyword", "unit_cost", "float", "rcvd", "date"), fields);
    }

    @Test
    void numbersAndBooleansPassThrough() {
        assertEquals(12.5, Indices.shorten(12.5));
        assertEquals(true, Indices.shorten(true));
    }

    @Test
    void anEmptyIndexIsNotWorthAnalysing() {
        assertEquals(0, Indices.documents(Map.of("documents", 0L)));
        assertEquals(24, Indices.documents(Map.of("documents", 24L)));
    }

    @Test
    void anIndexWeCouldNotCountIsAnalysedAnyway() {
        // -1, not 0: not knowing is a reason to look, not a reason to skip
        assertEquals(-1, Indices.documents(null));
        assertEquals(-1, Indices.documents(Map.of("values", Map.of())));
        assertEquals(-1, Indices.documents("not a map"));
    }

    @Test
    void aBriefingThatDecodesNothingIsStoredButNotSent() {
        // stored so the index is not analysed again on every request, never sent because
        // it says nothing the mapping does not. The two are different needs.
        assertEquals("Nothing here needs decoding: the field names and values say what they mean.",
                     Analysis.NOTHING_TO_DECODE);

        Map<String, String> briefings = new LinkedHashMap<>();
        briefings.put("inventory", "dept: FW = footwear");
        briefings.put("products", Analysis.NOTHING_TO_DECODE);
        briefings.values().removeIf(Analysis.NOTHING_TO_DECODE::equals);

        assertEquals(Set.of("inventory"), briefings.keySet());
    }

    @Test
    void everyFieldAQueryMayNameIsListed() {
        Map<String, Object> mapping = Map.of("properties", Map.of(
            "price", Map.of("type", "float"),
            "name", Map.of("type", "text", "fields", Map.of("keyword", Map.of("type", "keyword"))),
            "supplier", Map.of("properties", Map.of("city", Map.of("type", "keyword")))));

        Set<String> fields = Indices.fields(mapping);
        assertTrue(fields.contains("price"), fields.toString());
        // a text field's sub-field is a legal thing to sort on, so it counts as known
        assertTrue(fields.contains("name") && fields.contains("name.keyword"), fields.toString());
        assertTrue(fields.contains("supplier.city"), fields.toString());
        assertFalse(fields.contains("made_up"), fields.toString());
    }

    @Test
    void aMappingWithNoPropertiesYieldsNothingRatherThanThrowing() {
        assertEquals(Set.of(), Indices.fields(Map.of()));
        assertEquals(Set.of(), Indices.fields("not a mapping"));
        assertEquals(Set.of(), Indices.fields(null));
    }
}
