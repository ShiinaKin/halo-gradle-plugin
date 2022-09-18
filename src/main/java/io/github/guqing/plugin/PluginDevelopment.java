package io.github.guqing.plugin;

import java.io.File;
import java.util.Set;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;

/**
 * @author guqing
 * @since 2.0.0
 */
public class PluginDevelopment implements Plugin<Project> {
    public static final String HALO_SERVER_DEPENDENCY_CONFIGURATION_NAME = "haloServer";
    public static final String GROUP = "halo server";

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        System.out.println("Halo plugin development gradle plugin run...");

        HaloPluginExtension haloPluginExt = project.getExtensions()
            .create(HaloPluginExtension.EXTENSION_NAME, HaloPluginExtension.class, project);
        // populate plugin manifest info
        File manifestFile = getPluginManifest(project);
        haloPluginExt.setManifestFile(manifestFile);

        PluginManifest pluginManifest = YamlUtils.read(manifestFile, PluginManifest.class);
        haloPluginExt.setRequire(pluginManifest.getSpec().getRequire());
        haloPluginExt.setVersion(pluginManifest.getSpec().getVersion());

        project.getTasks()
            .register(PluginComponentsIndexTask.TASK_NAME, PluginComponentsIndexTask.class, it -> {
                it.setGroup(GROUP);
                FileCollection files =
                    project.getExtensions().getByType(SourceSetContainer.class).getByName("main")
                        .getOutput().getClassesDirs();
                it.classesDirs.from(files);
            });
        project.getTasks().getByName("build")
            .finalizedBy(PluginComponentsIndexTask.TASK_NAME);

        project.getTasks().register(InstallHaloTask.TASK_NAME, InstallHaloTask.class, it -> {
            it.setDescription("Install Halo server executable jar locally.");
            it.setGroup(GROUP);
            Configuration configuration =
                project.getConfigurations().create(HALO_SERVER_DEPENDENCY_CONFIGURATION_NAME);
            it.configurationProperty.set(configuration);
            it.serverRepository.set(haloPluginExt.getServerRepository());
        });

        project.getTasks().register(HaloServerTask.TASK_NAME, HaloServerTask.class, it -> {
            it.setDescription("Run Halo server locally with the plugin being developed");
            it.setGroup(GROUP);
            it.pluginEnvProperty.set(haloPluginExt);
            it.haloHome.set(haloPluginExt.getWorkDir());
            it.manifest.set(haloPluginExt.getManifestFile());
            it.dependsOn(InstallHaloTask.TASK_NAME);
        });
    }

    private File getPluginManifest(Project project) {
        SourceSetContainer sourceSetContainer =
            (SourceSetContainer) project.getProperties().get("sourceSets");
        File mainResourceDir = sourceSetContainer.stream()
            .filter(set -> "main".equals(set.getName()))
            .map(SourceSet::getResources)
            .map(SourceDirectorySet::getSrcDirs)
            .flatMap(Set::stream)
            .findFirst()
            .orElseThrow();

        for (String filename : HaloPluginExtension.MANIFEST) {
            File manifestFile = new File(mainResourceDir, filename);
            if (manifestFile.exists()) {
                return manifestFile;
            }
        }
        throw new IllegalStateException(
            "The plugin manifest file [plugin.yaml] not found in " + mainResourceDir);
    }
}
