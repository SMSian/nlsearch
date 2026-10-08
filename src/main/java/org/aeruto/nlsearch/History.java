package org.aeruto.nlsearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.index.IndexNotFoundException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chat history, so "now only the ones in stock" makes sense. One document per
 * session in the .nlsearch-history system index, holding the whole conversation;
 * only its tail is shown to the model, which has nowhere near room for all of it.
 * Everything here is best effort: a problem with the history never fails the
 * user's request.
 */
final class History {

    /** A registered system index, so Elasticsearch hides it and keeps users out of it. */
    static final String INDEX = ".nlsearch-history";
    static final String PATTERN = INDEX + "*";
    static final String ORIGIN = "nlsearch";

    /** Every turn is kept. This is only how many of them the model is shown. */
    static final int REPLAY = 10;

    private static final Logger logger = LogManager.getLogger(History.class);

    /** What was asked, what the model answered, how it went. */
    record Turn(String prompt, String answer, String outcome) {}

    private History() {}

    /** Writes go through this so Elasticsearch knows the plugin, not a user, is touching its own index. */
    private static Client asPlugin(NodeClient client) {
        return new OriginSettingClient(client, ORIGIN);
    }

    static List<Turn> load(NodeClient client, String session) {
        try {
            GetResponse doc = asPlugin(client).get(new GetRequest(INDEX, session)).actionGet();
            return doc.isExists() ? fromSource(doc.getSourceAsMap()) : List.of();
        } catch (IndexNotFoundException e) {
            return List.of(); // nobody has talked to us yet
        } catch (Exception e) {
            logger.warn("could not load the chat history of session [" + session + "], going on without it", e);
            return List.of();
        }
    }

    /**
     * Writes the turns and then calls {@code done}, whether that worked or not.
     * The caller answers the user only once this has finished: a chat bot sends
     * the next turn as soon as it gets the answer, and that turn has to be able
     * to read this one.
     */
    static void save(NodeClient client, String session, List<Turn> turns, Runnable done) {
        // create first, every time: the index has to be hidden so a search on "*" never reads other people's
        // chats, and a plain write would auto-create it visible if someone deleted it in between
        CreateIndexRequest create = new CreateIndexRequest(INDEX).settings(Map.of("index.hidden", true));
        asPlugin(client).admin().indices().create(create, ActionListener.wrap(ok -> write(client, session, turns, done), e -> {
            if (ExceptionsHelper.unwrapCause(e) instanceof ResourceAlreadyExistsException) {
                write(client, session, turns, done);
            } else {
                logger.warn("could not create the [" + INDEX + "] index, the chat history of session [" + session + "] is lost", e);
                done.run();
            }
        }));
    }

    private static void write(NodeClient client, String session, List<Turn> turns, Runnable done) {
        // refresh, so the next request in this conversation can read the turn back
        IndexRequest doc = new IndexRequest(INDEX).id(session).source(toSource(turns)).setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        asPlugin(client).index(doc, ActionListener.wrap(ok -> done.run(), e -> {
            logger.warn("could not save the chat history of session [" + session + "]", e);
            done.run();
        }));
    }

    /** The whole conversation, every turn of it. */
    static Map<String, Object> toSource(List<Turn> turns) {
        List<Map<String, String>> list = new ArrayList<>();
        for (Turn turn : turns) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("prompt", turn.prompt());
            map.put("answer", turn.answer());
            map.put("outcome", turn.outcome());
            list.add(map);
        }
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("updated", Instant.now().toString());
        source.put("turns", list);
        return source;
    }

    /** The tail of the conversation, which is all the model has room for. */
    static List<Turn> recent(List<Turn> turns) {
        return turns.size() > REPLAY ? turns.subList(turns.size() - REPLAY, turns.size()) : turns;
    }

    static List<Turn> fromSource(Map<String, Object> source) {
        List<Turn> turns = new ArrayList<>();
        if (source != null && source.get("turns") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    turns.add(new Turn(text(map.get("prompt")), text(map.get("answer")), text(map.get("outcome"))));
                }
            }
        }
        return turns;
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
