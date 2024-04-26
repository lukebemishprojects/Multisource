package dev.lukebemish.multisource.jarinjar;

import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.component.ComponentSelector;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ModuleComponentSelector;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.ResolvedVariantResult;
import org.gradle.api.artifacts.type.ArtifactTypeDefinition;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.jetbrains.annotations.Nullable;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class NestedArtifacts {
    private transient final SetProperty<ResolvedComponentResult> includedRootComponents;
    private transient final SetProperty<ResolvedArtifactResult> includedArtifacts;

    @Internal
    protected SetProperty<ResolvedComponentResult> getIncludedRootComponents() {
        return includedRootComponents;
    }

    @Internal
    protected SetProperty<ResolvedArtifactResult> getIncludedArtifacts() {
        return includedArtifacts;
    }

    @Inject
    protected abstract ObjectFactory getObjectFactory();

    @Nested
    public abstract ListProperty<ResolvedNestedJar> getResolvedArtifacts();

    public NestedArtifacts() {
        includedRootComponents = getObjectFactory().setProperty(ResolvedComponentResult.class);
        includedArtifacts = getObjectFactory().setProperty(ResolvedArtifactResult.class);

        includedArtifacts.finalizeValueOnRead();
        includedRootComponents.finalizeValueOnRead();

        getResolvedArtifacts().set(getIncludedRootComponents().zip(getIncludedArtifacts(), NestedArtifacts::getIncludedJars));
    }

    public void configuration(Configuration jarJarConfiguration) {
        getIncludedArtifacts().addAll(jarJarConfiguration.getIncoming().artifactView(config ->
            config.attributes(
            attr -> attr.attribute(ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE, ArtifactTypeDefinition.JAR_TYPE)
            )
        ).getArtifacts().getResolvedArtifacts());
        getIncludedRootComponents().add(jarJarConfiguration.getIncoming().getResolutionResult().getRootComponent());
    }

    public void setConfigurations(Collection<? extends Configuration> configurations) {
        getIncludedArtifacts().empty();
        getIncludedRootComponents().empty();
        for (Configuration configuration : configurations) {
            configuration(configuration);
        }
    }

    private static List<ResolvedNestedJar> getIncludedJars(Set<ResolvedComponentResult> rootComponents, Set<ResolvedArtifactResult> artifacts) {
        Map<ModuleLocation, String> versions = new HashMap<>();
        Map<ModuleLocation, String> versionRanges = new HashMap<>();
        Set<ModuleLocation> knownIdentifiers = new HashSet<>();

        for (ResolvedComponentResult rootComponent : rootComponents) {
            collectFromComponent(rootComponent, knownIdentifiers, versions, versionRanges);
        }
        List<ResolvedNestedJar> data = new ArrayList<>();
        for (ResolvedArtifactResult result : artifacts) {
            ResolvedVariantResult variant = result.getVariant();

            ModuleSelector artifactIdentifier = capabilityOrModule(variant);
            if (artifactIdentifier == null) {
                continue;
            }

            ModuleLocation jarIdentifier = new ModuleLocation(artifactIdentifier.location().group(), artifactIdentifier.location().artifact());
            if (!knownIdentifiers.contains(jarIdentifier)) {
                continue;
            }

            String version = versions.get(jarIdentifier);
            if (version == null) {
                version = getVersionFrom(variant);
            }

            String versionRange = versionRanges.get(jarIdentifier);
            if (versionRange == null) {
                versionRange = makeOpenRange(variant);
            }

            if (version != null && versionRange != null) {
                data.add(new ResolvedNestedJar(result.getFile(), version, versionRange, jarIdentifier.group(), jarIdentifier.artifact()));
            }
        }
        return data.stream()
            .sorted(Comparator.comparing(d -> d.getModuleGroup() + ":" + d.getModuleName()))
            .collect(Collectors.toList());
    }

    private static void collectFromComponent(ResolvedComponentResult rootComponent, Set<ModuleLocation> knownIdentifiers, Map<ModuleLocation, String> versions, Map<ModuleLocation, String> versionRanges) {
        for (DependencyResult result : rootComponent.getDependencies()) {
            if (!(result instanceof ResolvedDependencyResult resolvedResult)) {
                continue;
            }
            ComponentSelector requested = resolvedResult.getRequested();
            ResolvedVariantResult variant = resolvedResult.getResolvedVariant();

            ModuleSelector artifactIdentifier = capabilityOrModule(variant);
            if (artifactIdentifier == null) {
                continue;
            }

            ModuleLocation jarIdentifier = new ModuleLocation(artifactIdentifier.location().group(), artifactIdentifier.location().artifact());
            knownIdentifiers.add(jarIdentifier);

            String versionRange = null;
            if (requested instanceof ModuleComponentSelector requestedModule) {
                if (isValidVersionRange(requestedModule.getVersionConstraint().getStrictVersion())) {
                    versionRange = requestedModule.getVersionConstraint().getStrictVersion();
                } else if (isValidVersionRange(requestedModule.getVersionConstraint().getRequiredVersion())) {
                    versionRange = requestedModule.getVersionConstraint().getRequiredVersion();
                } else if (isValidVersionRange(requestedModule.getVersionConstraint().getPreferredVersion())) {
                    versionRange = requestedModule.getVersionConstraint().getPreferredVersion();
                } if (isValidVersionRange(requestedModule.getVersion())) {
                    versionRange = requestedModule.getVersion();
                }
            }
            if (versionRange == null) {
                versionRange = makeOpenRange(variant);
            }

            String version = getVersionFrom(variant);

            if (version != null) {
                versions.put(jarIdentifier, version);
            }
            if (versionRange != null) {
                versionRanges.put(jarIdentifier, versionRange);
            }
        }
    }

    private static @Nullable ModuleSelector capabilityOrModule(final ResolvedVariantResult variant) {
        ModuleSelector moduleIdentifier = null;
        if (variant.getOwner() instanceof ModuleComponentIdentifier moduleComponentIdentifier) {
            moduleIdentifier = new ModuleSelector(
                new ModuleLocation(
                    moduleComponentIdentifier.getGroup(),
                    moduleComponentIdentifier.getModule()
                ),
                moduleComponentIdentifier.getVersion()
            );
        }

        List<ModuleSelector> capabilityIdentifiers = variant.getCapabilities().stream()
            .map(capability -> new ModuleSelector(
                new ModuleLocation(
                    capability.getGroup(),
                    capability.getName()
                ),
                capability.getVersion()
            ))
            .toList();

        if (moduleIdentifier != null && capabilityIdentifiers.contains(moduleIdentifier)) {
            return moduleIdentifier;
        } else if (capabilityIdentifiers.isEmpty()) {
            return null;
        }
        return capabilityIdentifiers.get(0);
    }

    private static @Nullable String moduleOrCapabilityVersion(final ResolvedVariantResult variant) {
        ModuleSelector identifier = capabilityOrModule(variant);
        if (identifier != null) {
            return identifier.version();
        }
        return null;
    }

    private static @Nullable String makeOpenRange(final ResolvedVariantResult variant) {
        String baseVersion = moduleOrCapabilityVersion(variant);

        if (baseVersion == null) {
            return null;
        }

        return "[" + baseVersion + ",)";
    }

    private static @Nullable String getVersionFrom(final ResolvedVariantResult variant) {
        return moduleOrCapabilityVersion(variant);
    }

    private static boolean isValidVersionRange(final @Nullable String range) {
        if (range == null) {
            return false;
        }
        try {
            final VersionRange data = VersionRange.createFromVersionSpec(range);
            return data.hasRestrictions() && data.getRecommendedVersion() == null && !range.contains("+");
        } catch (InvalidVersionSpecificationException e) {
            return false;
        }
    }
}
