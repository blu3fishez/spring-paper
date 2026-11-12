package org.blu3fishez.springpaper.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import xyz.jpenilla.runpaper.RunPaperPlugin;
import xyz.jpenilla.runpaper.task.RunServer;
import xyz.jpenilla.runtask.service.DownloadsAPIService;

public final class SpringPaperPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        HostPluginSetup.apply(project, "paper");
        var extension = project.getExtensions().create("springPaper", SpringPaperExtension.class);
        var legacy = HostPluginSetup.legacy(project, extension.getConfigurationFile(), "springPaper.configuration");
        var target = extension.getPaper();
        var api = target.getApiCoordinate().map(value -> LegacyBuildSettings.coordinate(value, "", "Paper apiCoordinate is required"))
                .orElse(legacy.map(values -> {
                    var config = LegacyBuildSettings.configuration(values);
                    String minecraft = config.text("spring.paper.minecraft.version", "");
                    return LegacyBuildSettings.coordinate(config.text("spring.paper.minecraft.api-coordinate", minecraft.isBlank() ? "" : minecraft + ".build.+"),
                            "io.papermc.paper:paper-api", "Compilation requires springPaper.paper.apiCoordinate (or legacy minecraft.version/api-coordinate)");
                }));
        project.getDependencies().addProvider("compileOnly", api);
        var shadow = HostPluginSetup.packaging(project, "paper", extension.getConfigurationFile(), target.getApiVersion());
        project.getPluginManager().apply(RunPaperPlugin.class);
        var development = extension.getDevelopment();
        HostPluginSetup.development(project, development, legacy, "spring.paper.development");
        var version = development.getMinecraftVersion().orElse(legacy.map(values -> {
            String value = LegacyBuildSettings.configuration(values).text("spring.paper.minecraft.version", "");
            return value.isBlank() ? null : value;
        }));
        var build = development.getBuild().orElse(legacy.map(values -> {
            int value = LegacyBuildSettings.configuration(values).integer("spring.paper.minecraft.build", 0);
            return value > 0 ? value : null;
        }));
        project.getTasks().register("preparePaperServer", PrepareHostServer.class, task ->
                task.configure("paper", shadow.flatMap(value -> value.getArchiveFile()), development));
        project.getTasks().named("runServer", RunServer.class).configure(task -> {
            task.dependsOn(shadow);
            task.getVersion().set(version);
            task.getBuild().set(build.filter(value -> value > 0)
                    .<DownloadsAPIService.Build>map(DownloadsAPIService.Build.Specific::new)
                    .orElse(DownloadsAPIService.Build.Latest.INSTANCE));
            task.getRunDirectory().set(development.getDirectory());
            task.getJvmArgumentProviders().add(new DevelopmentJvmArguments(development.getJvmArgs()));
            var session = new NativeLaunchSession();
            var artifact = shadow.flatMap(value -> value.getArchiveFile());
            task.doFirst(new RunHostPreparation("paper", artifact, project.getLayout().getBuildDirectory().file("spring-paper/native-plan.txt"), development.getAcceptEula(), development.getServerProperties(), session));
            task.doLast(new RunHostFinalization(session));
        });
    }
}
