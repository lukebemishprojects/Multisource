package dev.lukebemish.multiloader;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import org.gradle.api.artifacts.dsl.DependencyCollector;

import javax.inject.Inject;

@SuppressWarnings("UnstableApiUsage")
public abstract class FabricDependenciesSetup extends DependenciesSetup {
    public abstract DependencyCollector getLoader();

    @Inject
    public FabricDependenciesSetup(LoomGradleExtensionAPI loom) {
        super(loom);
    }
}
