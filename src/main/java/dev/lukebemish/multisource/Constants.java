package dev.lukebemish.multisource;

import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.plugins.JavaPlugin;

import java.util.Locale;

public final class Constants {
    public static final String SOURCES_ELEMENTS = JavaPlugin.SOURCES_ELEMENTS_CONFIGURATION_NAME;

    public static final String TO_REMAP_RUNTIME_CLASSPATH = "toRemapRuntimeClasspath";
    public static final String RUNTIME_CLASSPATH = JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME;
    public static final String RUNTIME_CLASSPATH_EXPOSED = RUNTIME_CLASSPATH+"Exposed";
    public static final String RUNTIME_ELEMENTS = JavaPlugin.RUNTIME_ELEMENTS_CONFIGURATION_NAME;

    public static final String TO_REMAP_COMPILE_CLASSPATH = "toRemapCompileClasspath";
    public static final String COMPILE_CLASSPATH = JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME;
    public static final String COMPILE_CLASSPATH_EXPOSED = COMPILE_CLASSPATH+"Exposed";
    public static final String API_ELEMENTS = JavaPlugin.API_ELEMENTS_CONFIGURATION_NAME;

    public static final String RUNTIME_MOD_CLASSES = "runtimeModClasses";
    public static final String LOCAL_RUNTIME = "localRuntime";
    public static final String LOCAL_IMPLEMENTATION = "localImplementation";
    public static final String API = JavaPlugin.API_CONFIGURATION_NAME;
    public static final String IMPLEMENTATION = JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME;
    public static final String COMPILE_ONLY = JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME;
    public static final String RUNTIME_ONLY = JavaPlugin.RUNTIME_ONLY_CONFIGURATION_NAME;
    public static final String COMPILE_ONLY_API = JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME;

    public static final String OUTPUT_JAR = "outputJar";
    public static final String OUTPUT_SOURCES_JAR = "outputSourcesJar";
    public static final String INCLUDE = "include";

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
