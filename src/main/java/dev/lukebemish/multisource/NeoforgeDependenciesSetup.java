package dev.lukebemish.multisource;

import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.DependencyCollector;

import javax.inject.Inject;

@SuppressWarnings("UnstableApiUsage")
public abstract class NeoforgeDependenciesSetup extends DependenciesSetup {
    public abstract DependencyCollector getNeoForge();

    @Inject
    public NeoforgeDependenciesSetup(Project project) {
        super(project);
    }
}
