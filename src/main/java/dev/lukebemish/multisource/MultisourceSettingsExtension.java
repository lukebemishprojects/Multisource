package dev.lukebemish.multisource;

import org.gradle.api.Action;
import org.gradle.api.initialization.Settings;

import javax.inject.Inject;

public abstract class MultisourceSettingsExtension {
    private final Settings settings;

    @Inject
    public MultisourceSettingsExtension(Settings settings) {
        this.settings = settings;
    }

    public void of(String root, Action<ProjectSetup> action) {
        ProjectSetup projectSetup = new ProjectSetup(root, settings);
        action.execute(projectSetup);
    }
}
