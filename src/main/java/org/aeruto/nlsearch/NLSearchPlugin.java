package org.aeruto.nlsearch;

import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SystemIndexPlugin;
import org.elasticsearch.rest.RestHandler;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Entry point (see plugin-descriptor.properties): registers the settings and the _nl endpoint. */
public class NLSearchPlugin extends Plugin implements ActionPlugin, SystemIndexPlugin {

    private final Models models;

    public NLSearchPlugin(Settings settings) {
        models = new Models(settings);
    }

    /**
     * The chat history lives in a system index. Registering it is what lets the
     * name start with a dot without the deprecation warning, keeps it out of
     * every wildcard, and stops anyone writing to it by hand.
     */
    @Override
    public Collection<SystemIndexDescriptor> getSystemIndexDescriptors(Settings settings) {
        return List.of(
            SystemIndexDescriptor.builder()
                .setIndexPattern(History.PATTERN)
                .setDescription("Conversations held with the nlsearch _nl endpoint")
                .setType(SystemIndexDescriptor.Type.INTERNAL_UNMANAGED)
                .setOrigin(History.ORIGIN)
                .build()
        );
    }

    @Override
    public String getFeatureName() {
        return "nlsearch";
    }

    @Override
    public String getFeatureDescription() {
        return "Natural language access to Elasticsearch";
    }

    @Override
    public List<Setting<?>> getSettings() {
        return NLSettings.ALL;
    }

    @Override
    public Collection<?> createComponents(PluginServices services) {
        // so PUT _cluster/settings can switch model or provider without a restart
        services.clusterService().getClusterSettings().addSettingsUpdateConsumer(models::reload, NLSettings.ALL);
        return List.of();
    }

    @Override
    public Collection<RestHandler> getRestHandlers(
        RestHandlersServices services,
        Supplier<DiscoveryNodes> nodesInCluster,
        Predicate<NodeFeature> clusterSupportsFeature
    ) {
        return List.of(new NLRestHandler(models, clusterSupportsFeature));
    }
}
