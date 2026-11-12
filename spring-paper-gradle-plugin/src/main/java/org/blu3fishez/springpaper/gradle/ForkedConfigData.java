package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.gradle.api.GradleException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reads only launch prefixes in a child JVM using the runtime's own ConfigData implementation. */
final class ForkedConfigData {
    private ForkedConfigData() {}
    static Map<String, Object> read(Path java, Path artifact, Path dataDirectory, Path workingDirectory,
            Map<String, String> environment, Map<String, String> systemProperties, List<String> jvmArgs) throws IOException {
        Path output = Files.createTempFile("spring-paper-launch-", ".yml");
        try {
            try { Files.setPosixFilePermissions(output, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { }
            List<String> command = new ArrayList<>();
            command.add(java.toString());
            systemProperties.forEach((key, value) -> command.add("-D" + key + "=" + value));
            // JavaExec appends argument providers after its system properties, so their -D values win.
            for (String argument : jvmArgs) if (argument.startsWith("-D")) command.add(argument);
            command.addAll(List.of("-cp", artifact.toString(), "org.blu3fishez.springpaper.boot.config.ConfigDataProbe",
                    "--jar", artifact.toString(), "--data-dir", dataDirectory.toString(), "--output", output.toString()));
            var process = new ProcessBuilder(command);
            Files.createDirectories(workingDirectory);
            process.directory(workingDirectory.toFile());
            process.environment().clear();
            process.environment().putAll(environment);
            // ConfigData failures may contain resolved values. Do not relay child output into task logs.
            process.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process.redirectError(ProcessBuilder.Redirect.DISCARD);
            int code;
            try {
                var child = process.start();
                if (!child.waitFor(45, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    throw new GradleException("Pre-start ConfigData loading exceeded 45 seconds; no native host was started");
                }
                code = child.exitValue();
            }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new GradleException("Interrupted while preparing ConfigData", error); }
            if (code != 0) throw new GradleException("Pre-start ConfigData loading failed (details suppressed to protect configuration values). Check explicit profiles, imports, placeholders and launch configuration.");
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            try (var reader = Files.newBufferedReader(output)) {
                Object value = new Yaml(new SafeConstructor(options)).load(reader);
                if (!(value instanceof Map<?, ?> values)) throw new GradleException("Pre-start helper returned invalid launch settings");
                Map<String, Object> result = new LinkedHashMap<>();
                values.forEach((key, item) -> result.put(key.toString(), item));
                return result;
            }
        } finally { Files.deleteIfExists(output); }
    }
}
