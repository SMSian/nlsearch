package org.aeruto.nlsearch;

import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.core.TimeValue;

import java.util.List;

/**
 * Goes in elasticsearch.yml, or at runtime:
 * PUT _cluster/settings {"persistent": {"nlsearch.provider": "openai", "nlsearch.model": "gpt-4o-mini", "nlsearch.api_key": "sk-..."}}
 */
final class NLSettings {

    static final List<String> PROVIDERS = List.of("ollama", "openai", "anthropic", "gemini");

    static final Setting<String> PROVIDER = Setting.simpleString("nlsearch.provider", "ollama", value -> {
        if (PROVIDERS.contains(value) == false) {
            throw new IllegalArgumentException("nlsearch.provider must be one of " + PROVIDERS + ", not [" + value + "]");
        }
    }, Property.NodeScope, Property.Dynamic);

    static final Setting<String> MODEL = Setting.simpleString("nlsearch.model", "qwen2.5-coder:7b", Property.NodeScope, Property.Dynamic);

    // empty = the provider's usual address
    static final Setting<String> URL = Setting.simpleString("nlsearch.url", Property.NodeScope, Property.Dynamic);

    // Filtered hides it from the settings apis
    static final Setting<String> API_KEY = Setting.simpleString("nlsearch.api_key", Property.NodeScope, Property.Dynamic, Property.Filtered);

    static final Setting<TimeValue> TIMEOUT = Setting.timeSetting("nlsearch.timeout", TimeValue.timeValueSeconds(60), Property.NodeScope, Property.Dynamic);

    static final List<Setting<?>> ALL = List.of(PROVIDER, MODEL, URL, API_KEY, TIMEOUT);

    private NLSettings() {}
}
