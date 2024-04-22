package dev.lukebemish.multisource;

import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyCollector;

import javax.inject.Inject;

@SuppressWarnings("UnstableApiUsage")
public abstract class DependenciesSetup {
    public abstract DependencyCollector getMinecraft();
    public abstract DependencyCollector getMappings();
    public LoomGradleExtensionAPI getLoom() {
        return loom;
    }
    public Project getProject() {
        return project;
    }

    private final LoomGradleExtensionAPI loom;
    private final Project project;

    @Inject
    public DependenciesSetup(Project project) {
        this.loom = project.getExtensions().getByType(LoomGradleExtensionAPI.class);
        this.project = project;
    }
}
