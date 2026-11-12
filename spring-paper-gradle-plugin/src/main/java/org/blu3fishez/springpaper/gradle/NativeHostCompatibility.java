package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.io.Serializable;
import org.gradle.api.GradleException;
import org.gradle.api.Task;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;

/** Runs before Gradle evaluates development inputs, which may have legacy YAML fallbacks. */
final class NativeHostCompatibility implements Spec<Task>, Serializable {
    static final String REQUIREMENT = "Native Velocity preparation requires @SpringVelocityApplication; legacy @Plugin applications keep their own configuration and can still be assembled";
    private final String host;
    private final Provider<RegularFile> artifact;
    NativeHostCompatibility(String host, Provider<RegularFile> artifact) { this.host = host; this.artifact = artifact; }
    @Override public boolean isSatisfiedBy(Task task) {
        try { NativeLaunchPreparation.validateMode(host, artifact.get().getAsFile().toPath()); }
        catch (IOException error) { throw new GradleException("Cannot read native application metadata", error); }
        return true;
    }
}
