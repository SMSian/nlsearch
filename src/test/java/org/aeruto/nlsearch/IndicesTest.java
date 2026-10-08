package org.aeruto.nlsearch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
