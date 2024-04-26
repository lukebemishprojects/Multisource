package dev.lukebemish.multisource.jarinjar;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

public class ResolvedNestedJar {
    private final File file;
    private final String version;
    private final String versionRange;
    private final String group;
    private final String artifact;

    public ResolvedNestedJar(File file, String version, String versionRange, String group, String artifact) {
        this.file = file;
        this.version = version;
        this.versionRange = versionRange;
        this.group = group;
        this.artifact = artifact;
    }

    public ModuleLocation createContainedJarIdentifier() {
        return new ModuleLocation(group, artifact);
    }

    public NeoContainedVersion createContainedVersion() {
        try {
            return new NeoContainedVersion(
                VersionRange.createFromVersionSpec(versionRange),
                new DefaultArtifactVersion(version)
            );
        } catch (InvalidVersionSpecificationException e) {
            throw new RuntimeException(e);
        }
    }

    public NeoMetadataEntry createContainerMetadata() {
        return new NeoMetadataEntry(createContainedVersion(), createContainedJarIdentifier(), "META-INF/jars/"+file.getName(), isObfuscated(file));
    }

    @InputFile
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public File getFile() {
        return file;
    }

    @Input
    public String getVersion() {
        return version;
    }

    @Input
    public String getVersionRange() {
        return versionRange;
    }

    @Input
    public String getModuleGroup() {
        return group;
    }

    @Input
    public String getModuleName() {
        return artifact;
    }

    private static boolean isObfuscated(final File dependency) {
        try(final JarFile jarFile = new JarFile(dependency)) {
            final Manifest manifest = jarFile.getManifest();
            return manifest.getMainAttributes().containsKey("Obfuscated-By");
        } catch (IOException e) {
            throw new RuntimeException("Could not read jar file for nested jar", e);
        }
    }
}
