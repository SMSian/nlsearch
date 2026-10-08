package org.aeruto.nlsearch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryTest {

    @Test
    void roundTripsThroughADocument() {
        List<History.Turn> turns = List.of(
            new History.Turn("show products", "{\"action\":\"search\"}", "3 hits"),
            new History.Turn("delete the first", "{\"action\":\"delete\"}", "failed: no id")
        );
        Map<String, Object> source = History.toSource(turns);
        assertTrue(source.containsKey("updated"));
        assertEquals(turns, History.fromSource(source));
    }

    @Test
    void everyTurnIsStored() {
        List<History.Turn> turns = new ArrayList<>();
        for (int i = 0; i < History.REPLAY + 5; i++) {
            turns.add(new History.Turn("turn " + i, "{}", "done"));
        }
        List<History.Turn> kept = History.fromSource(History.toSource(turns));
        assertEquals(turns.size(), kept.size());
        assertEquals("turn 0", kept.get(0).prompt());
        assertEquals("turn " + (History.REPLAY + 4), kept.get(kept.size() - 1).prompt());
    }

    @Test
    void onlyTheTailIsShownToTheModel() {
        List<History.Turn> turns = new ArrayList<>();
        for (int i = 0; i < History.REPLAY + 5; i++) {
            turns.add(new History.Turn("turn " + i, "{}", "done"));
        }
        List<History.Turn> shown = History.recent(turns);
        assertEquals(History.REPLAY, shown.size());
        assertEquals("turn 5", shown.get(0).prompt());
        assertEquals("turn " + (History.REPLAY + 4), shown.get(shown.size() - 1).prompt());

        // a short conversation is shown whole
        List<History.Turn> few = turns.subList(0, 3);
        assertEquals(few, History.recent(few));
    }

    @Test
    void itLivesInASystemIndex() {
        assertEquals(".nlsearch-history", History.INDEX);
        assertTrue(History.PATTERN.startsWith(History.INDEX));
    }

    @Test
    void garbageInTheDocumentIsIgnored() {
        assertTrue(History.fromSource(null).isEmpty());
        assertTrue(History.fromSource(Map.of()).isEmpty());
        assertTrue(History.fromSource(Map.of("turns", "nope")).isEmpty());
        List<History.Turn> turns = History.fromSource(Map.of("turns", List.of("nope", Map.of("prompt", "hi"))));
        assertEquals(1, turns.size());
        assertEquals(new History.Turn("hi", "", ""), turns.get(0));
    }
}
