package dev.lukebemish.multiloader;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import org.gradle.api.artifacts.dsl.DependencyCollector;

import javax.inject.Inject;

@SuppressWarnings("UnstableApiUsage")
public abstract class DependenciesSetup {
    public abstract DependencyCollector getMinecraft();
    public abstract DependencyCollector getMappings();
    public LoomGradleExtensionAPI getLoom() {
        return loom;
    }

    private final LoomGradleExtensionAPI loom;

    @Inject
    public DependenciesSetup(LoomGradleExtensionAPI loom) {
        this.loom = loom;
    }
}
