package org.aeruto.nlsearch;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.get.MultiGetItemResponse;
import org.elasticsearch.action.get.MultiGetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest.RefreshPolicy;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.client.internal.OriginSettingClient;
import org.elasticsearch.client.internal.node.NodeClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * What an index actually means, in the model's own words, kept so it does not
 * have to be worked out again on every request.
 *
 * Listing the values of a keyword field is not enough on its own. A field
 * called dept holding FW, AP and EQ tells a model nothing, and it will guess.
 * Once something has looked at the documents carrying each value and written
 * down that FW is footwear, every later question about shoes lands on the right
 * filter. That write-up is what this stores.
 *
 * One document per index, and optionally one per conversation, so a correction
 * made during a chat applies to that chat without changing what everyone else
 * sees.
 */
final class Analysis {

    static final String INDEX = ".nlsearch-analysis";
    static final String PATTERN = INDEX + "*";

    /**
     * Stored for an index whose values already say what they mean.
     *
     * It is stored rather than left blank so the index is not analysed again on every
     * request, and it is never sent to the model, which would learn nothing from it
     * that the mapping does not already say.
     */
    static final String NOTHING_TO_DECODE = "Nothing here needs decoding: the field names and values say what they mean.";

    private static final Logger logger = LogManager.getLogger(Analysis.class);

    /** What was learned about one index, and whether it still applies. */
    record Profile(String index, String summary, String fingerprint, String updated, String expires) {

        boolean stale(String currentFingerprint, Instant now) {
            if (fingerprint != null && fingerprint.equals(currentFingerprint) == false) {
                return true;   // the mapping changed under us
            }
            try {
                return expires != null && Instant.parse(expires).isBefore(now);
            } catch (Exception e) {
                return true;
            }
        }
    }

    private Analysis() {}

    private static Client asPlugin(NodeClient client) {
        return new OriginSettingClient(client, History.ORIGIN);
    }

    /** A conversation's own note wins over the one everybody shares. */
    static String id(String index, String session) {
        return session == null ? index : session + "::" + index;
    }

    /** Changes whenever the mapping does, so a profile cannot outlive the shape it describes. */
    static String fingerprint(Object mapping) {
        CRC32 crc = new CRC32();
        crc.update(String.valueOf(mapping).getBytes(StandardCharsets.UTF_8));
        return Long.toHexString(crc.getValue());
    }

    /**
     * The profiles for these indices, the session's own taking precedence.
     * Never throws: without a profile the model simply works harder.
     */
    static Map<String, Profile> load(NodeClient client, Collection<String> indices, String session) {
        if (indices.isEmpty()) {
            return Map.of();
        }
        MultiGetRequest request = new MultiGetRequest();
        List<String> wanted = new ArrayList<>();
        for (String index : indices) {
            if (session != null) {
                request.add(INDEX, id(index, session));
                wanted.add(index);
            }
            request.add(INDEX, id(index, null));
            wanted.add(index);
        }
        Map<String, Profile> found = new LinkedHashMap<>();
        try {
            MultiGetItemResponse[] items = asPlugin(client).multiGet(request).actionGet().getResponses();
            for (int i = 0; i < items.length && i < wanted.size(); i++) {
                if (items[i].isFailed() || items[i].getResponse() == null || items[i].getResponse().isExists() == false) {
                    continue;
                }
                // the session's entry is asked for first, so only fill a gap
                found.putIfAbsent(wanted.get(i), fromSource(items[i].getResponse().getSourceAsMap()));
            }
        } catch (Exception e) {
            logger.warn("could not read the stored analysis, carrying on without it", e);
        }
        return found;
    }

    static void save(NodeClient client, Profile profile, String session, Runnable done) {
        CreateIndexRequest create = new CreateIndexRequest(INDEX).settings(Map.of("index.hidden", true));
        asPlugin(client).admin().indices().create(create, ActionListener.wrap(ok -> write(client, profile, session, done), e -> {
            if (ExceptionsHelper.unwrapCause(e) instanceof ResourceAlreadyExistsException) {
                write(client, profile, session, done);
            } else {
                logger.warn("could not create the [" + INDEX + "] index, the analysis of [" + profile.index() + "] is lost", e);
                done.run();
            }
        }));
    }

    private static void write(NodeClient client, Profile profile, String session, Runnable done) {
        IndexRequest doc = new IndexRequest(INDEX).id(id(profile.index(), session))
            .source(toSource(profile, session))
            .setRefreshPolicy(RefreshPolicy.IMMEDIATE);
        asPlugin(client).index(doc, ActionListener.wrap(ok -> done.run(), e -> {
            logger.warn("could not store the analysis of [" + profile.index() + "]", e);
            done.run();
        }));
    }

    static Map<String, Object> toSource(Profile profile, String session) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("index", profile.index());
        if (session != null) {
            source.put("session", session);
        }
        source.put("summary", profile.summary());
        source.put("fingerprint", profile.fingerprint());
        source.put("updated", profile.updated());
        source.put("expires", profile.expires());
        return source;
    }

    static Profile fromSource(Map<String, Object> source) {
        return new Profile(text(source.get("index")), text(source.get("summary")),
                           text(source.get("fingerprint")), text(source.get("updated")), text(source.get("expires")));
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }
}
