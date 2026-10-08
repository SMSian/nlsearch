package org.aeruto.nlsearch;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xcontent.json.JsonXContent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON object the model answered with. Which fields are set depends on the action, see prompt.txt. */
record Plan(String action, String index, String id, Map<String, Object> body, List<Map<String, Object>> docs, String text) {

    // models like to wrap the JSON in ``` fences or put a sentence in front of it
    @SuppressWarnings("unchecked")
    static Plan parse(String answer) {
        if (answer == null || answer.isBlank()) {
            throw bad("the model answered nothing", answer);
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw bad("there is no JSON object in the model's answer", answer);
        }

        Map<String, Object> json;
        try {
            json = XContentHelper.convertToMap(JsonXContent.jsonXContent, answer.substring(start, end + 1), true);
        } catch (Exception e) {
            throw bad("the model's answer is not valid JSON (" + e.getMessage() + ")", answer);
        }

        if (json.get("action") instanceof String == false) {
            throw bad("the model's answer has no \"action\"", answer);
        }
        Object id = json.get("id"); // sometimes a number, we want text
        Object body = json.get("body");
        Object docs = json.get("docs");
        return new Plan(
            (String) json.get("action"),
            text(json.get("index")),
            id == null ? null : String.valueOf(id),
            body instanceof Map ? (Map<String, Object>) body : null,
            docs instanceof List ? (List<Map<String, Object>>) docs : null,
            text(json.get("text"))
        );
    }

    /** Back to a map, for the response and the chat history. */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("action", action);
        if (index != null) {
            map.put("index", index);
        }
        if (id != null) {
            map.put("id", id);
        }
        if (body != null) {
            map.put("body", body);
        }
        if (docs != null) {
            map.put("docs", docs);
        }
        if (text != null) {
            map.put("text", text);
        }
        return map;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static ElasticsearchStatusException bad(String why, String answer) {
        String shown = answer == null ? "" : (answer.length() > 2000 ? answer.substring(0, 2000) + "..." : answer);
        return new ElasticsearchStatusException(why + ". The answer was: " + shown, RestStatus.BAD_GATEWAY);
    }
}
