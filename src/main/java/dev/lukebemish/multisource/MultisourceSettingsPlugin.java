package dev.lukebemish.multisource;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

public class MultisourceSettingsPlugin implements Plugin<Settings> {
    @Override
    public void apply(Settings settings) {
        settings.getExtensions().create("multisource", MultisourceSettingsExtension.class, settings);
    }
}
