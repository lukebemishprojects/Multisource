package dev.lukebemish.multisource;

import org.gradle.api.artifacts.dsl.RepositoryHandler;

import java.util.Locale;

public final class Constants {
    public static final String SOURCES_ELEMENTS = "sourcesElements";

    public static final String RUNTIME_CLASSPATH_EXPOSED = "runtimeClasspathExposed";
    public static final String RUNTIME_CLASSPATH = "runtimeClasspath";
    public static final String RUNTIME_ELEMENTS = "runtimeElements";

    public static final String COMPILE_CLASSPATH_EXPOSED = "compileClasspathExposed";
    public static final String COMPILE_CLASSPATH = "compileClasspath";
    public static final String API_ELEMENTS = "apiElements";

    public static final String RUNTIME_MOD_CLASSES = "runtimeModClasses";

    public static void neoMaven(RepositoryHandler repositories) {
        repositories.maven(repo -> {
            repo.setUrl("https://maven.neoforged.net/");
            repo.setName("NeoForged");
        });
    }

    public static String forFeature(String feature, String config) {
        if (feature.equals("main")) {
            return config;
        }
        return feature + config.substring(0, 1).toUpperCase(Locale.ROOT) + config.substring(1);
    }

    private Constants() {}
}
