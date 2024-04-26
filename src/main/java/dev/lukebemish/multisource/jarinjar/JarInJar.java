package dev.lukebemish.multisource.jarinjar;

import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lukebemish.multisource.CopyArchiveFileTask;
import net.fabricmc.loom.LoomGradlePlugin;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.gradle.api.Task;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public abstract class JarInJar extends CopyArchiveFileTask {
    @Nested
    public abstract NestedArtifacts getJarJarArtifacts();

    @Input
    public abstract Property<Boolean> getMakeFabricJsons();

    @Input
    public abstract Property<Boolean> getMakeNeoMetadata();

    @Inject
    public JarInJar() {
        this.getMakeFabricJsons().convention(false);
        this.getMakeNeoMetadata().convention(false);
        this.doLast(this::addRemainingEntries);
    }

    public void configuration(Configuration jarJarConfiguration) {
        getJarJarArtifacts().configuration(jarJarConfiguration);
        dependsOn(jarJarConfiguration);
    }

    public void setConfigurations(Collection<? extends Configuration> configurations) {
        getJarJarArtifacts().setConfigurations(configurations);
        configurations.forEach(this::dependsOn);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private void addRemainingEntries(Task task) {
        List<ResolvedNestedJar> includedJars = getJarJarArtifacts().getResolvedArtifacts().get();
        Map<String, ByteProvider> entries = Maps.newHashMap();
        if (getMakeFabricJsons().get()) {
            includedJars.forEach(jar -> entries.put("META-INF/jars/"+jar.getFile().getName(), new ByteProvider.FileProvider(addFabricJsonIfMissing(jar))));
            if (!includedJars.isEmpty()) {
                try (ZipFile original = new ZipFile(getArchiveFile().get().getAsFile())) {
                    var fmj = original.getEntry("fabric.mod.json");
                    if (fmj != null) {
                        try (var is = original.getInputStream(fmj);
                             var reader = new InputStreamReader(is)) {
                            var json = GSON.fromJson(reader, JsonObject.class);
                            JsonArray jars;
                            if (json.has("jars")) {
                                jars = json.getAsJsonArray("jars");
                            } else {
                                jars = new JsonArray();
                            }
                            for (var jar : includedJars) {
                                JsonObject nestedJarEntry = new JsonObject();
                                nestedJarEntry.addProperty("file", "META-INF/jars/" + jar.getFile().getName());
                                jars.add(nestedJarEntry);
                            }
                            json.remove("jars");
                            json.add("jars", jars);
                            byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
                            entries.put("fabric.mod.json", new ByteProvider.DirectProvider(bytes));
                        } catch (Exception ignored) {
                            // Could not parse FMJ?
                            getLogger().warn("Could not parse fabric.mod.json from the original jar, ignoring.");
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        } else {
            includedJars.forEach(jar -> entries.put("META-INF/jars/"+jar.getFile().getName(), new ByteProvider.FileProvider(jar.getFile())));
        }
        if (getMakeNeoMetadata().get()) {
            if (!writeNeoMetadata(includedJars).jars().isEmpty()) {
                entries.put("META-INF/jarjar/metadata.json", new ByteProvider.FileProvider(netNeoMetadataPath().toFile()));
            }
        }
        try {
            appendZipEntries(getArchiveFile().get().getAsFile().toPath(), entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private sealed interface ByteProvider {
        byte[] bytes();

        record DirectProvider(byte[] bytes) implements ByteProvider {}
        record FileProvider(File file) implements ByteProvider {
            @Override
            public byte[] bytes() {
                try {
                    return FileUtils.readFileToByteArray(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private File addFabricJsonIfMissing(ResolvedNestedJar jar) {
        try(var zipFile = new ZipFile(jar.getFile())) {
            if (zipFile.getEntry("fabric.mod.json") != null) {
                return jar.getFile();
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read jar file for nested jar", e);
        }
        Path out = getTemporaryDir().toPath().resolve("generated-jars").resolve(jar.getFile().getName());
        try {
            FileUtils.copyFile(jar.getFile(), out.toFile());
            appendZipEntries(out, Map.of("fabric.mod.json", new ByteProvider.DirectProvider(generateFabricModJson(jar).getBytes(StandardCharsets.UTF_8))));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toFile();
    }

    private static void appendZipEntries(Path file, Map<String, ByteProvider> data) throws IOException {
        final Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");

        try (var zipIn = new ZipFile(file.toFile());
             var os = Files.newOutputStream(tempFile);
             var zipOut = new ZipOutputStream(os)) {
            var entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (data.containsKey(entry.getName())) {
                    continue;
                }
                zipOut.putNextEntry(entry);
                IOUtils.copy(zipIn.getInputStream(entry), zipOut);
                zipOut.closeEntry();
            }

            for (var entry : data.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                var zipEntry = new ZipEntry(entry.getKey());
                zipOut.putNextEntry(zipEntry);
                zipEntry.setTime(new GregorianCalendar(1980, Calendar.JANUARY, 1, 0, 0, 0).getTimeInMillis());
                zipOut.write(entry.getValue().bytes());
                zipOut.closeEntry();
            }
        }

        Files.move(tempFile, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String generateFabricModJson(ResolvedNestedJar jar) {
        String modId = (jar.getModuleGroup() + "_" + jar.getModuleName())
            .replaceAll("\\.", "_")
            .toLowerCase(Locale.ROOT);

        // Let's follow the convention that loom does so we match up
        if (modId.length() > 64) {
            String hash = Hashing.sha256()
                .hashString(modId, StandardCharsets.UTF_8)
                .toString();
            modId = modId.substring(0, 50) + hash.substring(0, 14);
        }

        final var jsonObject = new JsonObject();
        jsonObject.addProperty("schemaVersion", 1);

        jsonObject.addProperty("id", modId);
        jsonObject.addProperty("version", jar.getVersion());
        jsonObject.addProperty("name", jar.getModuleName());

        JsonObject custom = new JsonObject();
        custom.addProperty("dev.lukebemish.multisource:generated", true);
        jsonObject.add("custom", custom);

        return LoomGradlePlugin.GSON.toJson(jsonObject);
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private NeoMetadata writeNeoMetadata(List<ResolvedNestedJar> includedJars) {
        final Path metadataPath = netNeoMetadataPath();
        final NeoMetadata metadata = createNeoMetadata(includedJars);

        if (!metadata.jars().isEmpty()) {
            try {
                metadataPath.toFile().getParentFile().mkdirs();
                Files.deleteIfExists(metadataPath);
                Files.write(metadataPath, metadata.toJsonBytes(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write JarJar dependency metadata to disk.", e);
            }
        }
        return metadata;
    }

    private Path netNeoMetadataPath() {
        return getTemporaryDir().toPath().resolve("neoMetadata").resolve("metadata.json");
    }

    private NeoMetadata createNeoMetadata(List<ResolvedNestedJar> jars) {
        return new NeoMetadata(
            jars.stream()
                .map(ResolvedNestedJar::createContainerMetadata)
                .collect(Collectors.toList())
        );
    }
}
