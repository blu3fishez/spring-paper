package org.blu3fishez.springpaper.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import xyz.jpenilla.runvelocity.RunVelocityPlugin;
import xyz.jpenilla.runvelocity.task.RunVelocity;
import xyz.jpenilla.runtask.service.DownloadsAPIService;

public final class SpringVelocityPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        HostPluginSetup.apply(project, "velocity");
        var extension = project.getExtensions().create("springVelocity", SpringVelocityExtension.class);
        var legacy = HostPluginSetup.legacy(project, extension.getConfigurationFile(), "springVelocity.configuration");
        var api = extension.getApiCoordinate().map(value -> LegacyBuildSettings.coordinate(value, "", "Velocity apiCoordinate is required"))
                .orElse(legacy.map(values -> LegacyBuildSettings.coordinate(
                        LegacyBuildSettings.configuration(values).text("spring.velocity.api-coordinate", ""), "com.velocitypowered:velocity-api",
                        "Compilation requires springVelocity.apiCoordinate")));
        project.getDependencies().addProvider("compileOnly", api);
        var shadow = HostPluginSetup.packaging(project, "velocity", extension.getConfigurationFile(), null);
        project.getPluginManager().apply(RunVelocityPlugin.class);
        var development = extension.getDevelopment();
        HostPluginSetup.development(project, development, legacy, "spring.velocity.development");
        project.getTasks().register("prepareVelocityServer", PrepareHostServer.class, task ->
                task.configure("velocity", shadow.flatMap(value -> value.getArchiveFile()), development));
        project.getTasks().named("runVelocity", RunVelocity.class).configure(task -> {
            task.dependsOn(shadow);
            task.getVersion().set(development.getVersion());
            task.getBuild().set(development.getBuildNumber().map(DownloadsAPIService.Build.Specific::new));
            task.getRunDirectory().set(development.getDirectory());
            task.getJvmArgumentProviders().add(new DevelopmentJvmArguments(development.getJvmArgs()));
            var session = new NativeLaunchSession();
            var artifact = shadow.flatMap(value -> value.getArchiveFile());
            task.onlyIf(NativeHostCompatibility.REQUIREMENT, new NativeHostCompatibility("velocity", artifact));
            task.doFirst(new RunHostPreparation("velocity", artifact, project.getLayout().getBuildDirectory().file("spring-velocity/native-plan.txt"), development.getAcceptEula(), development.getServerProperties(), session));
            task.doLast(new RunHostFinalization(session));
        });
    }
}
