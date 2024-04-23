package dev.lukebemish.multisource;

import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import groovy.transform.stc.ClosureParams;
import groovy.transform.stc.SimpleType;
import net.fabricmc.loom.LoomRepositoryPlugin;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
import net.fabricmc.loom.api.RemapConfigurationSettings;
import net.fabricmc.loom.task.AbstractRemapJarTask;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.jetbrains.annotations.NotNull;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class ProjectSetup {
    private final Settings settings;
    private final String root;
    private final Map<String, SourceSetup> sources = new HashMap<>();
    private final List<Action<Project>> rootActions = new ArrayList<>();
    private final Map<String, LoaderSet> loaders = new HashMap<>();
    private final List<Action<RepositoryHandler>> repositories = new ArrayList<>();

    @Inject
    ProjectSetup(String root, Settings settings) {
        this.root = root;
        this.settings = settings;
        settings.getGradle().beforeProject(p -> {
            if (p.getPath().equals(root)) {
                rootActions.forEach(a -> a.execute(p));
            }
        });
        repositories.add(Constants::neoMaven);
        rootActions.add(p -> {
            p.getPluginManager().apply(LoomRepositoryPlugin.class);
        });
        rootActions.add(p -> {
            p.getPlugins().apply("java-library");
        });
        rootActions.add(p -> {
            var repositories = p.getRepositories();
            this.repositories.forEach(r -> r.execute(repositories));
        });
    }

    public void repositories(@ClosureParams(value = SimpleType.class, options = "org.gradle.api.artifacts.dsl.RepositoryHandler")
                             @DelegatesTo(RepositoryHandler.class) Closure<?> closure) {
        repositories(actionOf(closure));
    }

    public void repositories(Action<RepositoryHandler> repositories) {
        this.repositories.add(repositories);
    }

    public void common(String name, List<String> parents,
                       @ClosureParams(value = SimpleType.class, options = "dev.lukebemish.multiloader.DependenciesSetup")
                       @DelegatesTo(DependenciesSetup.class)
                       Closure<?> dependencies
    ) {
        common(name, parents, actionOf(dependencies));
    }

    public void common(String name,
                       @ClosureParams(value = SimpleType.class, options = "dev.lukebemish.multiloader.DependenciesSetup")
                       @DelegatesTo(DependenciesSetup.class)
                       Closure<?> dependencies
    ) {
        common(name, actionOf(dependencies));
    }

    public void common(String name, Action<DependenciesSetup> dependencies) {
        common(name, List.of(), dependencies);
    }

    @SuppressWarnings("UnstableApiUsage")
    public void common(String name, List<String> parents, Action<DependenciesSetup> dependencies) {
        SourceSetup setup = sources.computeIfAbsent(name, s -> new SourceSetup(root, name, settings));
        setup.doAction(p -> repositories.forEach(a -> a.execute(p.getRepositories())));
        setup.setPlatform("fabric");
        setup.doAction(ProjectSetup::exposeClasspathConfigurations);
        setup.doActionLate(p -> {
            var dependenciesSetup = p.getObjects().newInstance(DependenciesSetup.class, p);
            dependencies.execute(dependenciesSetup);
            p.getConfigurations().maybeCreate("minecraft").fromDependencyCollector(dependenciesSetup.getMinecraft());
            p.getConfigurations().maybeCreate("mappings").fromDependencyCollector(dependenciesSetup.getMappings());
        });

        var loader = loaders.computeIfAbsent(name, LoaderSet::new);
        parents.forEach(loader::parent);

        setup.doAction(p -> {
            var loom = p.getExtensions().getByType(LoomGradleExtensionAPI.class);
            loom.getRunConfigs().configureEach(run -> run.setIdeConfigGenerated(false));
        });

        rootActions.add(p -> {
            var set = createSourceSet(name, p);

            var compileOnly = Constants.forFeature(name, "compileOnly");
            p.getDependencies().add(compileOnly, p.getDependencies().project(Map.of("path", makeKey(root, name))));

            exposeModClasses(name, p, set);

            setupParents(p, name, loaders);

            setupCoreConfigurations(p, set);
        });
    }

    public void neoforge(String name, List<String> parents,
                        @ClosureParams(value = SimpleType.class, options = "dev.lukebemish.multiloader.NeoforgeDependenciesSetup")
                        @DelegatesTo(NeoforgeDependenciesSetup.class)
                        Closure<?> dependencies
    ) {
        neoforge(name, parents, actionOf(dependencies));
    }

    @SuppressWarnings("UnstableApiUsage")
    public void neoforge(String name, List<String> parents, Action<NeoforgeDependenciesSetup> dependencies) {
        SourceSetup setup = sources.computeIfAbsent(name, s -> new SourceSetup(root, name, settings));
        setup.doAction(p -> repositories.forEach(a -> a.execute(p.getRepositories())));
        setup.setPlatform("neoforge");
        setup.doAction(ProjectSetup::exposeClasspathConfigurations);
        setup.doAction(p -> {
            var loom = p.getExtensions().getByType(LoomGradleExtensionAPI.class);
            setupSubprojectConsumer(p, name, root, loom);
        });
        setup.doActionLate(p -> {
            var dependenciesSetup = p.getObjects().newInstance(NeoforgeDependenciesSetup.class, p);
            dependencies.execute(dependenciesSetup);
            p.getConfigurations().maybeCreate("minecraft").fromDependencyCollector(dependenciesSetup.getMinecraft());
            p.getConfigurations().maybeCreate("mappings").fromDependencyCollector(dependenciesSetup.getMappings());
            p.getConfigurations().maybeCreate("neoForge").fromDependencyCollector(dependenciesSetup.getNeoForge());
        });

        var loader = loaders.computeIfAbsent(name, LoaderSet::new);
        parents.forEach(loader::parent);

        rootActions.add(p -> {
            var set = createSourceSet(name, p);

            var compileOnly = Constants.forFeature(name, "compileOnly");
            p.getDependencies().add(compileOnly, p.getDependencies().project(Map.of("path", makeKey(root, name))));
            exposeModClasses(name, p, set);

            exposeRuntimeToSubproject(name, p);

            setupParents(p, name, loaders);

            setupCoreConfigurations(p, set);
            setupIncludeConfiguration(p, set);
        });
    }

    @SuppressWarnings("UnstableApiUsage")
    public void fabric(String name, List<String> parents, Action<FabricDependenciesSetup> dependencies) {
        SourceSetup setup = sources.computeIfAbsent(name, s -> new SourceSetup(root, name, settings));
        setup.doAction(p -> repositories.forEach(a -> a.execute(p.getRepositories())));
        setup.setPlatform("fabric");
        setup.doAction(ProjectSetup::exposeClasspathConfigurations);
        setup.doAction(p -> {
            var loom = p.getExtensions().getByType(LoomGradleExtensionAPI.class);
            setupSubprojectConsumer(p, name, root, loom);
            setupSubprojectRemappingConsumer(p, name, root, loom);
        });
        setup.doActionLate(p -> {
            var dependenciesSetup = p.getObjects().newInstance(FabricDependenciesSetup.class, p);
            dependencies.execute(dependenciesSetup);
            p.getConfigurations().maybeCreate("minecraft").fromDependencyCollector(dependenciesSetup.getMinecraft());
            p.getConfigurations().maybeCreate("mappings").fromDependencyCollector(dependenciesSetup.getMappings());
            p.getConfigurations().maybeCreate("modCompileOnly").fromDependencyCollector(dependenciesSetup.getLoader());
        });

        var loader = loaders.computeIfAbsent(name, LoaderSet::new);
        parents.forEach(loader::parent);

        rootActions.add(p -> {
            var set = createSourceSet(name, p);

            var compileOnly = Constants.forFeature(name, "compileOnly");
            var dep = (ModuleDependency) p.getDependencies().project(Map.of("path", makeKey(root, name)));
            dep.exclude(Map.of("group", "net.fabricmc", "module", "fabric-loader"));
            p.getDependencies().add(compileOnly, dep);
            exposeModClasses(name, p, set);

            exposeRuntimeToSubproject(name, p);

            setupParents(p, name, loaders);

            setupCoreConfigurations(p, set);
            setupRemapConfigurations(p, set);
            setupIncludeConfiguration(p, set);

            p.afterEvaluate(it -> {
                boolean hasJar = it.getTasks().getNames().contains(set.getTaskName(null, "jar"));
                boolean hasSourcesJar = it.getTasks().getNames().contains(set.getTaskName(null, "sourcesJar"));

                if (hasJar) {
                    var jar = it.getTasks().named(set.getTaskName(null, "jar"), Jar.class, t -> {
                        t.getArchiveClassifier().set(name + "-dev");
                        t.getDestinationDirectory().set(it.getLayout().getBuildDirectory().dir("devlibs"));
                    });
                    var outputJar = it.getConfigurations().maybeCreate(Constants.forFeature(name, Constants.OUTPUT_JAR));
                    outputJar.setCanBeResolved(false);
                    outputJar.setCanBeConsumed(true);
                    outputJar.getOutgoing().artifact(jar.get().getArchiveFile());

                    var outputJarConsumer = it.getConfigurations().maybeCreate(Constants.forFeature(name, Constants.OUTPUT_JAR + "Consumer"));
                    outputJarConsumer.setCanBeResolved(true);
                    outputJarConsumer.setCanBeConsumed(false);
                    outputJarConsumer.setTransitive(false);
                    it.getDependencies().add(outputJarConsumer.getName(), it.getDependencies().project(Map.of("path", makeKey(root, name), "configuration", Constants.OUTPUT_JAR + "Exposed")));

                    var remapJar = it.getTasks().register(set.getTaskName("remap", "jar"), CopySingleFileTask.class, t -> {
                        t.dependsOn(outputJarConsumer);
                        t.getInputFiles().from(outputJarConsumer);
                        t.getOutputClassifier().set(name);
                    });
                    it.getTasks().named("assemble", t -> t.dependsOn(remapJar.get()));

                    for (var configurationName : List.of(Constants.RUNTIME_ELEMENTS, Constants.API_ELEMENTS)) {
                        var config = it.getConfigurations().getByName(Constants.forFeature(name, configurationName));
                        config.getOutgoing().getArtifacts().clear();
                        config.getOutgoing().artifact(remapJar.get().getOutputFile());
                    }
                }

                if (hasSourcesJar) {
                    var sourcesJar = it.getTasks().named(set.getTaskName(null, "sourcesJar"), Jar.class, t -> {
                        t.getArchiveClassifier().set(name + "-sources-dev");
                        t.getDestinationDirectory().set(it.getLayout().getBuildDirectory().dir("devlibs"));
                    });

                    var outputSourcesJar = it.getConfigurations().maybeCreate(Constants.forFeature(name, Constants.OUTPUT_SOURCES_JAR));
                    outputSourcesJar.setCanBeResolved(false);
                    outputSourcesJar.setCanBeConsumed(true);
                    outputSourcesJar.getOutgoing().artifact(sourcesJar.get().getArchiveFile());

                    var outputSourcesJarConsumer = it.getConfigurations().maybeCreate(Constants.forFeature(name, Constants.OUTPUT_SOURCES_JAR + "Consumer"));
                    outputSourcesJarConsumer.setCanBeResolved(true);
                    outputSourcesJarConsumer.setCanBeConsumed(false);
                    outputSourcesJarConsumer.setTransitive(false);
                    it.getDependencies().add(outputSourcesJarConsumer.getName(), it.getDependencies().project(Map.of("path", makeKey(root, name), "configuration", Constants.OUTPUT_SOURCES_JAR + "Exposed")));

                    var remapSourcesJar = it.getTasks().register(set.getTaskName("remap", "sourcesJar"), CopySingleFileTask.class, t -> {
                        t.dependsOn(outputSourcesJarConsumer);
                        t.getInputFiles().from(outputSourcesJarConsumer);
                        t.getOutputClassifier().set(name+"-sources");
                    });
                    it.getTasks().named("assemble", t -> t.dependsOn(remapSourcesJar.get()));

                    var config = it.getConfigurations().getByName(Constants.forFeature(name, Constants.SOURCES_ELEMENTS));
                    config.getOutgoing().getArtifacts().clear();
                    config.getOutgoing().artifact(remapSourcesJar.get().getOutputFile());
                }
            });
        });
    }

    private static @NotNull SourceSet createSourceSet(String name, Project p) {
        var java = p.getExtensions().getByType(JavaPluginExtension.class);
        var sourceSets = java.getSourceSets();
        return sourceSets.maybeCreate(name);
    }

    public void fabric(String name, List<String> parents,
                       @ClosureParams(value = SimpleType.class, options = "dev.lukebemish.multiloader.FabricDependenciesSetup")
                       @DelegatesTo(FabricDependenciesSetup.class)
                       Closure<?> dependencies
    ) {
        fabric(name, parents, actionOf(dependencies));
    }

    private static void exposeModClasses(String name, Project p, SourceSet set) {
        var runtimeModClasses = p.getConfigurations().maybeCreate(Constants.forFeature(name, Constants.RUNTIME_MOD_CLASSES));
        runtimeModClasses.setCanBeConsumed(true);
        runtimeModClasses.setCanBeResolved(false);
        runtimeModClasses.getOutgoing().artifacts(p.provider(set::getOutput));
    }

    private static void setupParents(Project p, String name, Map<String, LoaderSet> loaders) {
        p.afterEvaluate(it -> {
            var sourcesPresent = it.getTasks().getNames().contains(Constants.forFeature(name, "sourcesJar"));
            var javadocPresent = it.getTasks().getNames().contains(Constants.forFeature(name, "javadoc"));
            var jarPresent = it.getTasks().getNames().contains(Constants.forFeature(name, "jar"));

            Set<String> parents = new HashSet<>();
            Set<String> visited = new LinkedHashSet<>();
            visited.add(name);
            traverseLoaders(loaders.get(name), loaders, parents, visited);

            var runtimeModClasses = p.getConfigurations().maybeCreate(Constants.forFeature(name, Constants.RUNTIME_MOD_CLASSES));
            for (String parent : loaders.get(name).getParents()) {
                // We only need direct parents here
                runtimeModClasses.extendsFrom(p.getConfigurations().getByName(Constants.forFeature(parent, Constants.RUNTIME_MOD_CLASSES)));
            }

            if (jarPresent) {
                it.getTasks().named(Constants.forFeature(name, "jar"), Jar.class, t -> {
                    for (String parent : parents) {
                        SourceSet sourceSet = it.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName(parent);
                        t.from(sourceSet.getOutput());
                        t.dependsOn(it.getTasks().getByName(sourceSet.getClassesTaskName()));
                        t.dependsOn(it.getTasks().getByName(sourceSet.getProcessResourcesTaskName()));
                    }
                });
            }

            if (sourcesPresent) {
                it.getTasks().named(Constants.forFeature(name, "sourcesJar"), Jar.class, t -> {
                    for (String parent : parents) {
                        SourceSet sourceSet = it.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName(parent);
                        t.from(sourceSet.getAllSource());
                    }
                });
            }

            if (javadocPresent) {
                it.getTasks().named(Constants.forFeature(name, "javadoc"), Javadoc.class, t -> {
                    for (String parent : parents) {
                        SourceSet sourceSet = it.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().getByName(parent);
                        t.source(sourceSet.getJava().getSourceDirectories());
                    }
                });
            }
        });
    }

    private static void traverseLoaders(LoaderSet loaderSet, Map<String, LoaderSet> loaders, Set<String> parents, Set<String> visited) {
        for (String parent : loaderSet.getParents()) {
            if (visited.contains(parent)) {
                throw new IllegalArgumentException("Circular dependency detected: " + String.join(" -> ", visited) + " -> " + parent);
            }
            parents.add(parent);
            var newVisited = new LinkedHashSet<>(visited);
            newVisited.add(parent);
            traverseLoaders(loaders.get(parent), loaders, parents, newVisited);
        }
    }

    private static void exposeRuntimeToSubproject(String name, Project p) {
        Configuration runtimeClasspathExposed = p.getConfigurations().maybeCreate(Constants.forFeature(name, Constants.RUNTIME_CLASSPATH_EXPOSED));
        runtimeClasspathExposed.extendsFrom(p.getConfigurations().getByName(Constants.forFeature(name, Constants.RUNTIME_CLASSPATH)));
        runtimeClasspathExposed.setCanBeConsumed(true);
        runtimeClasspathExposed.setCanBeResolved(false);
    }

    private Object makeKey(String root, String name) {
        if (root.equals(":")) {
            return ":" + name;
        }
        return root + ":" + name;
    }

    private static void setupSubprojectRemappingConsumer(Project p, String name, String root, LoomGradleExtensionAPI loom) {
        p.getDependencies().add("modCompileOnly", p.getDependencies().project(Map.of("path", root, "configuration", Constants.forFeature(name, Constants.TO_REMAP_COMPILE_CLASSPATH))));
        p.getDependencies().add("modRuntimeOnly", p.getDependencies().project(Map.of("path", root, "configuration", Constants.forFeature(name, Constants.TO_REMAP_RUNTIME_CLASSPATH))));
        p.getDependencies().add("include", p.getDependencies().project(Map.of("path", root, "configuration", Constants.forFeature(name, Constants.INCLUDE))));

        var outputJar = p.getConfigurations().maybeCreate(Constants.OUTPUT_JAR);
        var outputSourcesJar = p.getConfigurations().maybeCreate(Constants.OUTPUT_SOURCES_JAR);

        outputJar.setCanBeResolved(true);
        outputJar.setCanBeConsumed(false);
        outputJar.setTransitive(false);
        outputSourcesJar.setCanBeResolved(true);
        outputSourcesJar.setCanBeConsumed(false);
        outputSourcesJar.setTransitive(false);

        p.getDependencies().add(Constants.OUTPUT_JAR, p.getDependencies().project(Map.of("path", root, "configuration", Constants.forFeature(name, Constants.OUTPUT_JAR))));
        p.getDependencies().add(Constants.OUTPUT_SOURCES_JAR, p.getDependencies().project(Map.of("path", root, "configuration", Constants.forFeature(name, Constants.OUTPUT_SOURCES_JAR))));

        var remapJar = p.getTasks().named("remapJar", AbstractRemapJarTask.class, t -> {
            t.dependsOn(outputJar);
            t.getArchiveClassifier().set("output");
            t.getInputFile().fileProvider(p.provider(() -> outputJar.isEmpty() ? null : outputJar.getSingleFile()));
            t.onlyIf(it -> !outputJar.isEmpty());
        });

        var remapSourcesJar = p.getTasks().named("remapSourcesJar", AbstractRemapJarTask.class, t -> {
            t.dependsOn(outputSourcesJar);
            t.getArchiveClassifier().set("output-sources");
            t.getInputFile().fileProvider(p.provider(() -> outputSourcesJar.isEmpty() ? null : outputSourcesJar.getSingleFile()));
            t.onlyIf(it -> !outputSourcesJar.isEmpty());
        });

        var outputJarExposed = p.getConfigurations().maybeCreate(Constants.OUTPUT_JAR + "Exposed");
        var outputSourcesJarExposed = p.getConfigurations().maybeCreate(Constants.OUTPUT_SOURCES_JAR + "Exposed");

        outputJarExposed.getOutgoing().artifact(remapJar.get().getArchiveFile());
        outputSourcesJarExposed.getOutgoing().artifact(remapSourcesJar.get().getArchiveFile());
    }

    private static void setupSubprojectConsumer(Project p, String name, String root, LoomGradleExtensionAPI loom) {
        SourceSet runs = p.getExtensions().getByType(JavaPluginExtension.class).getSourceSets().maybeCreate("runs");
        p.getConfigurations().getByName("runsRuntimeClasspath").extendsFrom(p.getConfigurations().getByName(Constants.RUNTIME_CLASSPATH));
        Configuration runsModClasses = p.getConfigurations().maybeCreate("runsModClasses");
        runsModClasses.setTransitive(false);

        loom.mods(mods -> {
            mods.maybeCreate("main").configuration(runsModClasses);
        });
        loom.getRunConfigs().configureEach(run -> {
            run.setIdeConfigGenerated(true);
            run.setSource(runs);
        });

        p.getDependencies().add("runsRuntimeOnly", p.getDependencies().project(Map.of("path", root, "configuration", Constants.forFeature(name, Constants.RUNTIME_CLASSPATH_EXPOSED))));
        p.getDependencies().add("runsModClasses", p.getDependencies().project(Map.of("path", root, "configuration", Constants.forFeature(name, Constants.RUNTIME_MOD_CLASSES))));
    }

    private static void exposeClasspathConfigurations(Project p) {
        var configurations = p.getConfigurations();

        var runtimeElements = configurations.maybeCreate(Constants.RUNTIME_ELEMENTS);
        var apiElements = configurations.maybeCreate(Constants.API_ELEMENTS);
        var runtimeClasspath = configurations.maybeCreate(Constants.RUNTIME_CLASSPATH);
        var compileClasspath = configurations.maybeCreate(Constants.COMPILE_CLASSPATH);
        var runtimeClasspathExposed = configurations.maybeCreate(Constants.RUNTIME_CLASSPATH_EXPOSED);
        var compileClasspathExposed = configurations.maybeCreate(Constants.COMPILE_CLASSPATH_EXPOSED);

        copyAttributes(runtimeClasspath, runtimeClasspathExposed);
        runtimeElements.setCanBeConsumed(false);
        runtimeClasspathExposed.extendsFrom(runtimeClasspath);
        runtimeClasspathExposed.setCanBeConsumed(true);
        runtimeClasspathExposed.setCanBeResolved(false);

        copyAttributes(compileClasspath, compileClasspathExposed);
        apiElements.setCanBeConsumed(false);
        compileClasspathExposed.extendsFrom(compileClasspath);
        compileClasspathExposed.setCanBeConsumed(true);
        compileClasspathExposed.setCanBeResolved(false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copyAttributes(Configuration source, Configuration dest) {
        source.getAttributes().keySet().forEach(key ->
                dest.getAttributes().attribute((Attribute) key, Objects.requireNonNull(source.getAttributes().getAttribute(key)))
        );
    }

    private static <T> Action<T> actionOf(Closure<?> closure) {
        return t -> {
            closure.setDelegate(t);
            closure.call(t);
        };
    }

    private static void setupCoreConfigurations(Project p, SourceSet sourceSet) {
        var runtimeClasspath = p.getConfigurations().maybeCreate(sourceSet.getTaskName(null, Constants.RUNTIME_CLASSPATH));
        var compileClasspath = p.getConfigurations().maybeCreate(sourceSet.getTaskName(null, Constants.COMPILE_CLASSPATH));

        var localRuntime = p.getConfigurations().maybeCreate(sourceSet.getTaskName(null, Constants.LOCAL_RUNTIME));
        var localImplementation = p.getConfigurations().maybeCreate(sourceSet.getTaskName(null, Constants.LOCAL_IMPLEMENTATION));

        runtimeClasspath.extendsFrom(localRuntime);
        runtimeClasspath.extendsFrom(localImplementation);

        compileClasspath.extendsFrom(localImplementation);

        localImplementation.setCanBeConsumed(false);
        localImplementation.setCanBeResolved(false);

        localRuntime.setCanBeConsumed(false);
        localRuntime.setCanBeResolved(false);
    }

    private static void setupIncludeConfiguration(Project p, SourceSet sourceSet) {
        var include = p.getConfigurations().maybeCreate(sourceSet.getTaskName(null, Constants.INCLUDE));
        include.setCanBeResolved(false);
        include.setCanBeConsumed(true);
    }

    private static void setupRemapConfigurations(Project p, SourceSet sourceSet) {
        var toRemapRuntime = p.getConfigurations().maybeCreate(sourceSet.getTaskName(null, Constants.TO_REMAP_RUNTIME_CLASSPATH));
        toRemapRuntime.setCanBeResolved(false);
        toRemapRuntime.setCanBeConsumed(true);
        var toRemapCompile = p.getConfigurations().maybeCreate(sourceSet.getTaskName(null, Constants.TO_REMAP_COMPILE_CLASSPATH));
        toRemapCompile.setCanBeResolved(false);
        toRemapCompile.setCanBeConsumed(true);
        for (var target : RemapToCreate.TARGETS) {
            var name = sourceSet.getTaskName("mod", target.target());
            var conf = p.getConfigurations().maybeCreate(name);
            conf.setCanBeResolved(false);
            conf.setCanBeConsumed(false);
            if (target.compile) {
                toRemapCompile.extendsFrom(conf);
            }
            if (target.runtime) {
                toRemapRuntime.extendsFrom(conf);
            }
            p.afterEvaluate(it -> {
                if (
                    it.getConfigurations().getNames().contains(sourceSet.getTaskName(null, Constants.RUNTIME_ELEMENTS))
                    && it.getConfigurations().getNames().contains(sourceSet.getTaskName(null, Constants.API_ELEMENTS))
                ) {
                    var runtimeElements = p.getConfigurations().named(sourceSet.getTaskName(null, Constants.RUNTIME_ELEMENTS));
                    var apiElements = p.getConfigurations().named(sourceSet.getTaskName(null, Constants.API_ELEMENTS));
                    switch (target.publishingMode) {
                        case NONE -> {
                        }
                        case COMPILE_ONLY -> {
                            apiElements.configure(d -> d.extendsFrom(conf));
                        }
                        case RUNTIME_ONLY -> {
                            runtimeElements.configure(d -> d.extendsFrom(conf));
                        }
                        case COMPILE_AND_RUNTIME -> {
                            apiElements.configure(d -> d.extendsFrom(conf));
                            runtimeElements.configure(d -> d.extendsFrom(conf));
                        }
                    }
                }
            });
        }
    }

    private record RemapToCreate(String target, boolean compile, boolean runtime, RemapConfigurationSettings.PublishingMode publishingMode) {
        private static final List<RemapToCreate> TARGETS = List.of(
            new RemapToCreate(Constants.LOCAL_IMPLEMENTATION, true, true, RemapConfigurationSettings.PublishingMode.NONE),
            new RemapToCreate(Constants.LOCAL_RUNTIME, false, true, RemapConfigurationSettings.PublishingMode.NONE),
            new RemapToCreate(Constants.API, true, true, RemapConfigurationSettings.PublishingMode.COMPILE_AND_RUNTIME),
            new RemapToCreate(Constants.IMPLEMENTATION, true, true, RemapConfigurationSettings.PublishingMode.RUNTIME_ONLY),
            new RemapToCreate(Constants.COMPILE_ONLY, true, false, RemapConfigurationSettings.PublishingMode.NONE),
            new RemapToCreate(Constants.COMPILE_ONLY_API, true, false, RemapConfigurationSettings.PublishingMode.COMPILE_ONLY),
            new RemapToCreate(Constants.RUNTIME_ONLY, false, true, RemapConfigurationSettings.PublishingMode.RUNTIME_ONLY)
        );
    }
}
