package org.aeruto.nlsearch;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NLRestHandlerTest {

    @Test
    void longTextsAreCut() {
        String text = "x".repeat(200);
        assertEquals("x".repeat(80) + "...", NLRestHandler.shorten(text));
        assertEquals("short", NLRestHandler.shorten("short"));
    }

    @Test
    void bigListsAreCutAndNestedValuesToo() {
        Object shortened = NLRestHandler.shorten(Map.of("tags", List.of(1, 2, 3, 4, 5, 6, 7), "nested", Map.of("text", "y".repeat(100))));
        assertEquals(Map.of("tags", List.of(1, 2, 3, 4, 5), "nested", Map.of("text", "y".repeat(80) + "...")), shortened);
    }

    @Test
    void numbersAndBooleansPassThrough() {
        assertEquals(12.5, NLRestHandler.shorten(12.5));
        assertEquals(true, NLRestHandler.shorten(true));
    }
}
