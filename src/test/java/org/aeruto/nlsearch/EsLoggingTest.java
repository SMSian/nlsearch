package org.aeruto.nlsearch;

import org.elasticsearch.common.logging.LogConfigurator;
import org.junit.jupiter.api.BeforeAll;

/** Elasticsearch's own exception classes want the node's logging wired up, even in a unit test. */
abstract class EsLoggingTest {

    @BeforeAll
    static void elasticsearchLogging() {
        LogConfigurator.configureESLogging();
    }
}
