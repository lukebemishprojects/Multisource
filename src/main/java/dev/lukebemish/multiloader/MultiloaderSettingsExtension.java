package dev.lukebemish.multiloader;

import org.gradle.api.Action;
import org.gradle.api.initialization.Settings;

import javax.inject.Inject;

public abstract class MultiloaderSettingsExtension {
    private final Settings settings;

    @Inject
    public MultiloaderSettingsExtension(Settings settings) {
        this.settings = settings;
    }

    public void of(String root, Action<ProjectSetup> action) {
        ProjectSetup projectSetup = new ProjectSetup(root, settings);
        action.execute(projectSetup);
    }
}
