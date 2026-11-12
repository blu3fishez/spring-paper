package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "Checks mutable native files, external ConfigData and secret sources on every preparation")
public abstract class PrepareHostServer extends DefaultTask {
    @Input public abstract Property<String> getHost();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getArtifact();
    @Internal public abstract DirectoryProperty getRunDirectory();
    @Internal public abstract Property<JavaLauncher> getJavaLauncher();
    @Input public abstract Property<Boolean> getAcceptEula();
    @Input public abstract MapProperty<String, String> getServerProperties();
    @Input public abstract ListProperty<String> getNativeArguments();
    @Input public abstract ListProperty<String> getJvmArguments();
    @Internal public abstract MapProperty<String, String> getLaunchEnvironment();
    @Internal public abstract MapProperty<String, String> getLaunchSystemProperties();
    @OutputFile public abstract RegularFileProperty getPlanFile();
    @OutputFile public java.io.File getProposalFile() {
        return NativePlanReport.proposal(getPlanFile().get().getAsFile().toPath()).toFile();
    }

    public PrepareHostServer() {
        getNativeArguments().convention(List.of());
        getJvmArguments().convention(List.of());
        getAcceptEula().convention(false);
        getServerProperties().convention(Map.of());
        getLaunchEnvironment().convention(Map.of());
        getLaunchSystemProperties().convention(Map.of());
        getOutputs().upToDateWhen(task -> false);
    }
    void configure(String host, Provider<RegularFile> artifact, DevelopmentOptions development) {
        setGroup("spring " + host);
        setDescription("Prepares owned native files or writes a plan for an unmanaged run directory. Does not start the host.");
        getHost().set(host);
        getArtifact().set(artifact);
        if (host.equals("velocity")) onlyIf(NativeHostCompatibility.REQUIREMENT, new NativeHostCompatibility(host, artifact));
        getRunDirectory().set(development.getDirectory());
        getAcceptEula().set(development.getAcceptEula());
        getServerProperties().set(development.getServerProperties());
        getJvmArguments().set(development.getJvmArgs());
        getPlanFile().set(getProject().getLayout().getBuildDirectory().file("spring-" + host + "/native-plan.txt"));
        getJavaLauncher().set(getProject().getExtensions().getByType(JavaToolchainService.class)
                .launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(25))));
    }
    @TaskAction public void prepare() throws IOException {
        var environment = new java.util.LinkedHashMap<>(System.getenv());
        environment.putAll(getLaunchEnvironment().get());
        var result = NativeLaunchPreparation.prepare(getHost().get(), getArtifact().get().getAsFile().toPath(),
                getRunDirectory().get().getAsFile().toPath(), getJavaLauncher().get().getExecutablePath().getAsFile().toPath(),
                environment, NativeLaunchPreparation.springSystemProperties(getLaunchSystemProperties().get()),
                getJvmArguments().get(), getNativeArguments().get(), getAcceptEula().get(), getServerProperties().get(), getPlanFile().get().getAsFile().toPath());
        getLogger().lifecycle(result.owned() ? "Native configuration prepared; no host process was started." : "Native configuration is plan-only; existing files are unchanged.");
        getLogger().lifecycle("Review native settings in {}", getProposalFile());
    }
}
