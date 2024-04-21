package dev.lukebemish.multiloader;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import org.gradle.api.artifacts.dsl.DependencyCollector;

import javax.inject.Inject;

@SuppressWarnings("UnstableApiUsage")
public abstract class NeoforgeDependenciesSetup extends DependenciesSetup {
    public abstract DependencyCollector getNeoforge();

    @Inject
    public NeoforgeDependenciesSetup(LoomGradleExtensionAPI loom) {
        super(loom);
    }
}
