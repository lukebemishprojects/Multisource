# Multisource

Multisource is a tool for setting up a multiloader Minecraft modding development environment using source sets and feature
variants to model different loaders. It works by allowing the definition, in the `settings.gradle`, of different source sets
and information used to set up a subproject that uses [architectury loom](https://github.com/architectury/architectury-loom)
to set up Minecraft, remapping, and runs as necessary. The root project pulls a classpath from the subproject, and exposes
its compiled classes to the subproject for runs, as well as bouncing through the subproject to create remapped jars.

To use, first set up the desired projects in your `settings.gradle` file:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            name = 'Fabric'
            url = 'https://maven.fabricmc.net/'
        }
        maven {
            name = 'NeoForged'
            url = 'https://maven.neoforged.net'
        }
        maven {
            name = 'Architectury'
            url "https://maven.architectury.dev/"
        }
    }
}

plugins {
    id 'dev.lukebemish.multisource' version '<version>'
}

multisource.of(':') {
    common('main', []) {
        minecraft.add project.libs.minecraft
        mappings.add loom.officialMojangMappings()
    }
    fabric('fabric', ['main']) {
        minecraft.add project.libs.minecraft
        mappings.add loom.officialMojangMappings()
        loader.add project.libs.fabric.loader
    }
    neoforge('neoforge', ['main']) {
        minecraft.add project.libs.minecraft
        mappings.add loom.officialMojangMappings()
        neoForge.add project.libs.neoforge
    }
}
```

The `multisource.of` block will set of the projects automatically, not requiring any manual configuration. The example above
sets up the `main` source set as a common source set, and then sets up the `fabric` and `neoforge` source sets as the
appropriate platforms. Both loader-specific source sets will automatically pull from the common `main` source set set up
as a parent. Mapping and minecraft or loader dependency information may be specified in either the `settings.gradle` DSL
as above, or by making `build.gradle` files for the generated subprojects and providing it there.

Next, you will want to set up feature variants and publishing in the root `build.gradle`:

```groovy
plugins {
    id "maven-publish"
}

dependencies {
    modFabricImplementation libs.fabric.loader
    modFabricImplementation libs.fabric.api
}

java {
    withSourcesJar()
    registerFeature("neoforge") {
        usingSourceSet sourceSets.neoforge
        capability(project.group, project.name, project.version)
        capability(project.group, "$project.name-neoforge", project.version)
        withSourcesJar()
    }
    registerFeature("fabric") {
        usingSourceSet sourceSets.fabric
        capability(project.group, project.name, project.version)
        capability(project.group, "$project.name-fabric", project.version)
        withSourcesJar()
    }
}

publishing {
    publications {
        mavenJava(MavenPublication) {
            from components.java
        }
    }
}
```

Multisource sets up remapping configurations for fabric source sets, as well as include configurations for jar-in-jar in
the fabric and neoforge source sets. By publishing with `components.java`, the different feature variants are included
in the gradle module metadata with distinct capabilities.
