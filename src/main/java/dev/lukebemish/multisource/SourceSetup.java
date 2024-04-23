package dev.lukebemish.multisource;

import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.initialization.Settings;
import org.gradle.api.plugins.ExtraPropertiesExtension;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

public class SourceSetup {
    private final Settings settings;
    private final String project;
    private final String name;
    private final List<Action<Project>> setupActions = new ArrayList<>();
    private final List<Action<Project>> setupActionsLate = new ArrayList<>();
    private String platform = "fabric";

    @Inject
    SourceSetup(String project, String name, Settings settings) {
        this.project = project;
        this.settings = settings;
        this.name = name;

        String key = (project.equals(":") ? "" : project) + ":" + name;
        settings.include(key);
        settings.getGradle().beforeProject(p -> {
            if (p.getPath().equals(key)) {
                executeOnProject(p);
            }
        });
    }

    private void executeOnProject(Project p) {
        ExtraPropertiesExtension ext = p.getExtensions().getExtraProperties();
        ext.set("loom.platform", platform);
        this.setupActionsLate.forEach(p::afterEvaluate);
        applyArchLoom(p);
        setupActions.forEach(d -> d.execute(p));
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public void doAction(Action<Project> dependencies) {
        this.setupActions.add(dependencies);
    }

    private void applyArchLoom(Project project) {
        project.getPluginManager().apply("dev.architectury.loom");
    }
}
