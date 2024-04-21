package dev.lukebemish.multiloader;

import org.gradle.api.Plugin;
import org.gradle.api.initialization.Settings;

public class MultiloaderSettingsPlugin implements Plugin<Settings> {
    @Override
    public void apply(Settings settings) {
        settings.getExtensions().create("multiloader", MultiloaderSettingsExtension.class, settings);
    }
}
