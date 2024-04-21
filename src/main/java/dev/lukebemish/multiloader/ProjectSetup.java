package dev.lukebemish.multiloader;

import groovy.lang.Closure;
import groovy.lang.DelegatesTo;
import groovy.transform.stc.ClosureParams;
import groovy.transform.stc.SimpleType;
import net.fabricmc.loom.api.LoomGradleExtensionAPI;
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
    private final List<Action<Project>> rootAfterActions = new ArrayList<>();
    private final Map<String, LoaderSet> loaders = new HashMap<>();
    private List<Action<RepositoryHandler>> repositories = new ArrayList<>();

    @Inject
    ProjectSetup(String root, Settings settings) {
        this.root = root;
        this.settings = settings;
        settings.getGradle().beforeProject(p -> {
            if (p.getPath().equals(root)) {
                rootActions.forEach(a -> a.execute(p));
            }
        });
        settings.getGradle().afterProject(p -> {
            if (p.getPath().equals(root)) {
                rootAfterActions.forEach(a -> a.execute(p));
            }
        });
        rootActions.add(p -> {
            var ext = p.getExtensions().getExtraProperties();
            ext.set("fabric.loom.disableRemappedVariants", false);
            p.getPlugins().apply("dev.architectury.loom");
        });
        rootActions.add(p -> {
            var repositories = p.getRepositories();
            this.repositories.forEach(r -> r.execute(repositories));
            Constants.neoMaven(repositories);
        });
    }

    public void repositories(Action<RepositoryHandler> repositories) {
        this.repositories.add(repositories);
    }

    public void common(String name, List<String> parents,
                       @ClosureParams(value = SimpleType.class, options = "dev.lukebemish.multiloader.DependenciesSetup")
                       @DelegatesTo(DependenciesSetup.class)
                       Closure<?> dependencies
    ) {
        common(name, parents, ofAction(dependencies));
    }

    public void common(String name,
                       @ClosureParams(value = SimpleType.class, options = "dev.lukebemish.multiloader.DependenciesSetup")
                       @DelegatesTo(DependenciesSetup.class)
                       Closure<?> dependencies
    ) {
        common(name, ofAction(dependencies));
    }

    public void common(String name, Action<DependenciesSetup> dependencies) {
        common(name, List.of(), dependencies);
    }

    @SuppressWarnings("UnstableApiUsage")
    public void common(String name, List<String> parents, Action<DependenciesSetup> dependencies) {
        SourceSetup setup = sources.computeIfAbsent(name, s -> new SourceSetup(root, name, settings));
        setup.setPlatform("fabric");
        if (!name.equals("main")) {
            setup.doAction(ProjectSetup::exposeClasspathConfigurations);
        }
        setup.doAction(p -> {
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
            var sourceSets = p.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            var set = sourceSets.maybeCreate(name);

            if (name.equals("main")) {
                // undo fabric stuff
                p.getTasks().named("remapJar", t -> t.setEnabled(false));
                p.getTasks().named("remapSourcesJar", t -> t.setEnabled(false));
                p.getTasks().named("jar", Jar.class, t -> {
                    t.getArchiveClassifier().set("");
                    t.getDestinationDirectory().set(p.getLayout().getBuildDirectory().dir("libs"));
                });
                p.afterEvaluate(it -> {
                    if (!it.getTasks().getNames().contains("sourcesJar")) {
                        // No sources jar, so return
                        return;
                    }
                    it.getTasks().named("sourcesJar", Jar.class, t -> {
                        t.getArchiveClassifier().set("sources");
                        t.getDestinationDirectory().set(p.getLayout().getBuildDirectory().dir("libs"));
                    });
                });
                for (String configName : List.of(Constants.API_ELEMENTS, Constants.RUNTIME_ELEMENTS)) {
                    var config = p.getConfigurations().maybeCreate(configName);
                    config.getOutgoing().getArtifacts().removeIf(a -> a.getBuildDependencies().getDependencies(null).contains(p.getTasks().getByName("remapJar")));
                }
                var sourcesConfig = p.getConfigurations().maybeCreate(Constants.SOURCES_ELEMENTS);
                sourcesConfig.getOutgoing().getArtifacts().removeIf(a -> a.getBuildDependencies().getDependencies(null).contains(p.getTasks().getByName("remapSourcesJar")));
            } else {
                var compileOnly = Constants.forFeature(name, "compileOnly");
                p.getDependencies().add(compileOnly, p.getDependencies().project(Map.of("path", makeKey(root, name))));
            }

            exposeModClasses(name, p, set);

            setupParents(p, name, loaders);
        });
    }

    public void neoforge(String name, List<String> parents,
                        @ClosureParams(value = SimpleType.class, options = "dev.lukebemish.multiloader.NeoforgeDependenciesSetup")
                        @DelegatesTo(NeoforgeDependenciesSetup.class)
                        Closure<?> dependencies
    ) {
        neoforge(name, parents, ofAction(dependencies));
    }

    @SuppressWarnings("UnstableApiUsage")
    public void neoforge(String name, List<String> parents, Action<NeoforgeDependenciesSetup> dependencies) {
        if (name.equals("main")) {
            throw new IllegalArgumentException("Main source set cannot be fabric");
        }
        SourceSetup setup = sources.computeIfAbsent(name, s -> new SourceSetup(root, name, settings));
        setup.setPlatform("fabric");
        setup.doAction(ProjectSetup::exposeClasspathConfigurations);
        setup.doAction(p -> {
            var loom = p.getExtensions().getByType(LoomGradleExtensionAPI.class);
            setupSubprojectConsumer(p, name, root, loom);
        });
        setup.doAction(p -> {
            var dependenciesSetup = p.getObjects().newInstance(NeoforgeDependenciesSetup.class, p);
            dependencies.execute(dependenciesSetup);
            p.getConfigurations().maybeCreate("minecraft").fromDependencyCollector(dependenciesSetup.getMinecraft());
            p.getConfigurations().maybeCreate("mappings").fromDependencyCollector(dependenciesSetup.getMappings());
            p.getConfigurations().maybeCreate("neoforge").fromDependencyCollector(dependenciesSetup.getNeoforge());
        });

        var loader = loaders.computeIfAbsent(name, LoaderSet::new);
        parents.forEach(loader::parent);

        rootActions.add(p -> {
            var sourceSets = p.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            var set = sourceSets.maybeCreate(name);

            var compileOnly = Constants.forFeature(name, "compileOnly");
            p.getDependencies().add(compileOnly, p.getDependencies().project(Map.of("path", makeKey(root, name))));
            exposeModClasses(name, p, set);

            exposeRuntimeToSubproject(name, p);

            setupParents(p, name, loaders);
        });
    }

    @SuppressWarnings("UnstableApiUsage")
    public void fabric(String name, List<String> parents, Action<FabricDependenciesSetup> dependencies) {
        if (name.equals("main")) {
            throw new IllegalArgumentException("Main source set cannot be fabric");
        }
        SourceSetup setup = sources.computeIfAbsent(name, s -> new SourceSetup(root, name, settings));
        setup.setPlatform("fabric");
        setup.doAction(ProjectSetup::exposeClasspathConfigurations);
        setup.doAction(p -> {
            var loom = p.getExtensions().getByType(LoomGradleExtensionAPI.class);
            setupSubprojectConsumer(p, name, root, loom);
        });
        setup.doAction(p -> {
            var dependenciesSetup = p.getObjects().newInstance(FabricDependenciesSetup.class, p);
            dependencies.execute(dependenciesSetup);
            p.getConfigurations().maybeCreate("minecraft").fromDependencyCollector(dependenciesSetup.getMinecraft());
            p.getConfigurations().maybeCreate("mappings").fromDependencyCollector(dependenciesSetup.getMappings());
            p.getConfigurations().maybeCreate("modCompileOnly").fromDependencyCollector(dependenciesSetup.getLoader());
        });

        var loader = loaders.computeIfAbsent(name, LoaderSet::new);
        parents.forEach(loader::parent);

        rootActions.add(p -> {
            var sourceSets = p.getExtensions().getByType(JavaPluginExtension.class).getSourceSets();
            var set = sourceSets.maybeCreate(name);

            var compileOnly = Constants.forFeature(name, "compileOnly");
            var dep = (ModuleDependency) p.getDependencies().project(Map.of("path", makeKey(root, name)));
            dep.exclude(Map.of("group", "net.fabricmc", "module", "fabric-loader"));
            p.getDependencies().add(compileOnly, dep);
            exposeModClasses(name, p, set);

            exposeRuntimeToSubproject(name, p);

            setupParents(p, name, loaders);

            var loom = p.getExtensions().getByType(LoomGradleExtensionAPI.class);
            loom.createRemapConfigurations(set);
        });

        // TODO: set up remap
    }

    public void fabric(String name, List<String> parents,
                       @ClosureParams(value = SimpleType.class, options = "dev.lukebemish.multiloader.FabricDependenciesSetup")
                       @DelegatesTo(FabricDependenciesSetup.class)
                       Closure<?> dependencies
    ) {
        fabric(name, parents, ofAction(dependencies));
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
        if (name.equals("main")) {
            return root;
        }
        if (root.equals(":")) {
            return ":" + name;
        }
        return root + ":" + name;
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

    private static <T> Action<T> ofAction(Closure<?> closure) {
        return t -> {
            closure.setDelegate(t);
            closure.call(t);
        };
    }
}
