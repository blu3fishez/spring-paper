package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipFile;
import org.gradle.api.GradleException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** One launch plan feeds standalone prepare and the final native JavaExec action. */
final class NativeLaunchPreparation {
    record Jvm(Path executable, Map<String, String> environment, Map<String, String> systemProperties, List<String> arguments) {
        @Override public String toString() { return "Launch JVM [configuration=redacted]"; }
    }
    record NativeFiles(List<String> arguments, boolean acceptEula, Map<String, String> serverProperties) {
        @Override public String toString() { return "Native file inputs [configuration=redacted]"; }
    }
    record Inputs(String host, Path artifact, Path runDirectory, Jvm jvm, NativeFiles nativeFiles, Path planFile) {
        @Override public String toString() { return "Native launch inputs [host=" + host + ", configuration=redacted]"; }
    }
    record Result(boolean owned, String variable, String secret) {
        @Override public String toString() { return "Native launch preparation [owned=" + owned + ", secret=redacted]"; }
    }
    private NativeLaunchPreparation() {}
    static boolean isLegacyPaper(Path artifact) throws IOException {
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            Properties metadata = readMetadata(jar);
            return metadata.getProperty("host", "").equals("paper") && metadata.getProperty("mode", "application").equals("legacy");
        }
    }
    private static Properties readMetadata(ZipFile jar) throws IOException {
        var entry = jar.getEntry("META-INF/spring-paper/application.properties");
        if (entry == null) throw new GradleException("Application artifact is missing its own root metadata");
        Properties metadata = new Properties();
        try (var reader = new java.io.InputStreamReader(jar.getInputStream(entry), java.nio.charset.StandardCharsets.UTF_8)) { metadata.load(reader); }
        return metadata;
    }
    static void validateMode(String host, Path artifact) throws IOException {
        if (!host.equals("velocity")) return;
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            requireSupportedMode(host, readMetadata(jar));
        }
    }
    private static void requireSupportedMode(String host, Properties metadata) {
        if (host.equals("velocity") && metadata.getProperty("mode", "application").equals("legacy")) {
            throw new GradleException(NativeHostCompatibility.REQUIREMENT);
        }
    }
    static Result prepare(Inputs inputs) throws IOException {
        String host = inputs.host();
        Path artifact = inputs.artifact();
        Path root = inputs.runDirectory().toAbsolutePath().normalize();
        Path planFile = inputs.planFile();
        Jvm jvm = inputs.jvm();
        NativeFiles nativeFiles = inputs.nativeFiles();
        Properties metadata;
        Map<String, Object> descriptor;
        try (ZipFile jar = new ZipFile(artifact.toFile())) {
            metadata = readMetadata(jar);
            if (!host.equals(metadata.getProperty("host"))) throw new GradleException("Application artifact host does not match launch task");
            var nativeEntry = jar.getEntry(host.equals("paper") ? "plugin.yml" : "velocity-plugin.json");
            if (nativeEntry == null) throw new GradleException("Application artifact is missing its native descriptor");
            try (var input = jar.getInputStream(nativeEntry)) {
                descriptor = NativeConfiguration.map(new Yaml(new SafeConstructor(new LoaderOptions())).load(input));
            }
        }
        requireSupportedMode(host, metadata);
        if (host.equals("paper")) NativeConfiguration.validatePaperTargets(root, nativeFiles.arguments());
        if (host.equals("paper") && metadata.getProperty("mode", "application").equals("legacy")) {
            prepareLegacyPaper(root, nativeFiles.serverProperties(), nativeFiles.acceptEula());
            NativePlanReport.write(planFile, host, "legacy-compatibility", new NativeConfiguration.Plan(Map.of(
                    root.resolve("server.properties"), Files.readString(root.resolve("server.properties")),
                    root.resolve("eula.txt"), Files.readString(root.resolve("eula.txt"))), null, null, false));
            return new Result(true, null, null);
        }
        String name = descriptor.get(host.equals("paper") ? "name" : "id").toString();
        if (name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new GradleException("Native plugin metadata does not identify a safe plugin data directory");
        }
        Path dataDirectory = root.resolve("plugins").resolve(name).normalize();
        var loaded = ForkedConfigData.read(jvm.executable(), artifact.toAbsolutePath(), dataDirectory, root, jvm.environment(), jvm.systemProperties(), jvm.arguments());
        var projection = new NativeValueTypes.Projection(NativeConfiguration.map(loaded.get("native-field-values")), NativeConfiguration.map(loaded.get("native-map-values")));
        var plan = host.equals("paper")
                ? NativeConfiguration.paper(root, NativeConfiguration.map(loaded.get("paper-velocity")), nativeFiles.arguments(), nativeFiles.acceptEula(), nativeFiles.serverProperties(), projection)
                : NativeConfiguration.velocity(root, NativeConfiguration.map(loaded.get("velocity-server")), projection);
        // Plan-only trees never require opening secret sources or change any native file.
        boolean owned = OwnedRunDirectory.materialize(root, host, plan, planFile);
        if (!owned) return new Result(false, plan.secretVariable(), null);
        String secret = ForwardingSecret.resolve(plan, jvm.environment());
        return new Result(true, plan.secretVariable(), secret);
    }
    static Map<String, String> springSystemProperties(Map<String, ?> additional) {
        Map<String, String> result = new LinkedHashMap<>();
        System.getProperties().forEach((key, value) -> { if (key.toString().startsWith("spring.")) result.put(key.toString(), value.toString()); });
        additional.forEach((key, value) -> result.put(key, value.toString()));
        return result;
    }
    private static void prepareLegacyPaper(Path root, Map<String, String> overrides, boolean acceptEula) throws IOException {
        Files.createDirectories(root);
        Properties properties = new Properties();
        Path server = root.resolve("server.properties");
        if (Files.exists(server)) try (var input = Files.newInputStream(server)) { properties.load(input); }
        properties.putAll(overrides);
        try (var writer = Files.newBufferedWriter(server)) { properties.store(writer, "Spring Paper development server"); }
        Files.writeString(root.resolve("eula.txt"), "# https://aka.ms/MinecraftEULA\neula=" + acceptEula + "\n");
    }
}
