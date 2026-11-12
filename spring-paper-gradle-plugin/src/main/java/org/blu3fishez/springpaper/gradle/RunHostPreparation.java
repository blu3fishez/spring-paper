package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import xyz.jpenilla.runtask.service.DownloadsAPIService;
import xyz.jpenilla.runtask.task.AbstractRun;

/** Final execution-time preparation observes actual JavaExec arguments and environment. */
final class RunHostPreparation implements Action<Task>, Serializable {
    private final String host;
    private final Provider<RegularFile> artifact;
    private final Provider<RegularFile> planFile;
    private final Provider<Boolean> acceptEula;
    private final Provider<Map<String, String>> serverProperties;
    private final NativeLaunchSession session;
    RunHostPreparation(String host, Provider<RegularFile> artifact, Provider<RegularFile> planFile, Provider<Boolean> acceptEula, Provider<Map<String, String>> serverProperties, NativeLaunchSession session) {
        this.host = host;
        this.artifact = artifact;
        this.planFile = planFile;
        this.acceptEula = acceptEula;
        this.serverProperties = serverProperties;
        this.session = session;
    }
    @Override public void execute(Task value) {
        AbstractRun task = (AbstractRun) value;
        Path jar = artifact.get().getAsFile().toPath();
        Path plan = planFile.get().getAsFile().toPath();
        Map<String, String> environment = new LinkedHashMap<>();
        task.getEnvironment().forEach((key, item) -> environment.put(key, item.toString()));
        var systemProperties = NativeLaunchPreparation.springSystemProperties(task.getSystemProperties());
        // Forward Spring launch properties to the native process as well as its pre-start reader.
        task.systemProperties(systemProperties);
        List<String> jvm = new java.util.ArrayList<>(task.getJvmArgs());
        for (var provider : task.getJvmArgumentProviders()) provider.asArguments().forEach(jvm::add);
        try {
            boolean legacyPaper = host.equals("paper") && NativeLaunchPreparation.isLegacyPaper(jar);
            if (task.getRunClasspath().isEmpty() && (!task.getVersion().isPresent() || task.getVersion().get().isBlank()
                    || (!legacyPaper && (!(task.getBuild().getOrNull() instanceof DownloadsAPIService.Build.Specific build) || build.getBuildNumber() <= 0)))) {
                String setting = host.equals("paper") ? "springPaper.development.minecraftVersion" + (legacyPaper ? " (or legacy minecraft.version)" : " and build")
                        : "springVelocity.development.version and buildNumber";
                throw new GradleException("Native execution requires explicit " + setting + (legacyPaper ? "; the existing run-paper build selector is preserved" : "; no latest version or build is inferred"));
            }
            var inputs = new NativeLaunchPreparation.Inputs(host, jar, task.getRunDirectory().get().getAsFile().toPath(),
                    new NativeLaunchPreparation.Jvm(task.getJavaLauncher().get().getExecutablePath().getAsFile().toPath(), environment, systemProperties, jvm),
                    new NativeLaunchPreparation.NativeFiles(task.getArgs() == null ? List.of() : task.getArgs(), acceptEula.get(), serverProperties.get()), plan);
            var result = NativeLaunchPreparation.prepare(inputs);
            if (!result.owned()) throw new GradleException("Native launch blocked: run directory or effective CLI target is unmanaged and preparation is plan-only. Review " + plan);
            Path root = task.getRunDirectory().get().getAsFile().toPath();
            if (java.nio.file.Files.exists(root.resolve(".spring-paper/owned.properties"))) session.snapshot = OwnedRunDirectory.snapshot(root, host);
            if (result.secret() != null) task.environment(result.variable(), result.secret());
        } catch (IOException error) { throw new GradleException("Could not prepare native launch configuration", error); }
    }
}
