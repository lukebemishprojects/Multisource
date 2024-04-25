package dev.lukebemish.multisource;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.BasePluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public abstract class CopyArchiveFileTask extends DefaultTask {
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getInputFiles();

    @OutputFile
    public abstract RegularFileProperty getArchiveFile();

    @Internal
    public abstract Property<String> getArchiveClassifier();

    @Internal
    public abstract Property<String> getArchiveExtension();

    @Internal
    public abstract DirectoryProperty getDestinationDirectory();

    @Internal
    public abstract Property<String> getArchiveBaseName();

    @Internal
    public abstract Property<String> getArchiveVersion();

    @Inject
    public CopyArchiveFileTask() {
        getArchiveClassifier().convention("");
        getArchiveExtension().convention("jar");
        getArchiveVersion().convention(getProject().provider(() -> getProject().getVersion().toString()));

        BasePluginExtension basePluginExtension = getProject().getExtensions().getByType(BasePluginExtension.class);
        getArchiveBaseName().convention(basePluginExtension.getArchivesName());

        getDestinationDirectory().convention(getProject().getLayout().getBuildDirectory().dir("libs"));

        Provider<String> nameWithVersion = getArchiveBaseName().zip(getArchiveVersion(), (name, version) -> name + "-" + version);
        Provider<String> nameWithVersionWithClassifier = nameWithVersion.zip(getArchiveClassifier(), (name, classifier) -> classifier.isEmpty()? name : name + "-" + classifier);
        Provider<String> fullFileName = nameWithVersionWithClassifier.zip(getArchiveExtension(), (name, extension) -> name + "." + extension);
        getArchiveFile().convention(getDestinationDirectory().file(fullFileName));
    }

    @TaskAction
    public void run() {
        File input = getInputFiles().getSingleFile();
        File output = getArchiveFile().get().getAsFile();
        try {
            Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
