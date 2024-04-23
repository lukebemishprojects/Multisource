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

public abstract class CopySingleFileTask extends DefaultTask {
    @InputFiles
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getInputFiles();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    @Internal
    public abstract Property<String> getOutputClassifier();

    @Internal
    public abstract Property<String> getOutputExtension();

    @Internal
    public abstract DirectoryProperty getOutputDirectory();

    @Internal
    public abstract Property<String> getOutputName();

    @Internal
    public abstract Property<String> getOutputVersion();

    @Inject
    public CopySingleFileTask() {
        getOutputClassifier().convention("");
        getOutputExtension().convention("jar");
        getOutputVersion().convention(getProject().provider(() -> getProject().getVersion().toString()));

        BasePluginExtension basePluginExtension = getProject().getExtensions().getByType(BasePluginExtension.class);
        getOutputName().convention(basePluginExtension.getArchivesName());

        getOutputDirectory().convention(getProject().getLayout().getBuildDirectory().dir("libs"));

        Provider<String> nameWithVersion = getOutputName().zip(getOutputVersion(), (name, version) -> name + "-" + version);
        Provider<String> nameWithVersionWithClassifier = nameWithVersion.zip(getOutputClassifier(), (name, classifier) -> classifier.isEmpty()? name : name + "-" + classifier);
        Provider<String> fullFileName = nameWithVersionWithClassifier.zip(getOutputExtension(), (name, extension) -> name + "." + extension);
        getOutputFile().convention(getOutputDirectory().file(fullFileName));
    }

    @TaskAction
    public void run() {
        File input = getInputFiles().getSingleFile();
        File output = getOutputFile().get().getAsFile();
        try {
            Files.copy(input.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
