package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.blu3fishez.springpaper.config.YamlDocument;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import groovy.json.JsonOutput;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@CacheableTask
public abstract class GeneratePluginDescriptor extends DefaultTask {
    @Classpath public abstract ConfigurableFileCollection getClasses();
    @Classpath public abstract ConfigurableFileCollection getLibraries();
    @Internal public abstract RegularFileProperty getConfigurationFile();
    @Input public abstract Property<String> getHost();
    @Input @Optional public abstract Property<String> getApiVersion();
    @Input public abstract Property<String> getPluginVersion();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    public GeneratePluginDescriptor() { getHost().convention("paper"); }

    private PluginMetadataReader metadata() throws IOException {
        PluginMetadataReader reader = new PluginMetadataReader();
        for (var file : getClasses()) reader.read(file.toPath(), true);
        for (var file : getLibraries()) reader.read(file.toPath(), false);
        if (reader.applications.size() != 1) throw new GradleException("Exactly one application host and root is required; found " + reader.applications.keySet());
        String root = reader.applications.keySet().iterator().next();
        if (!getHost().get().equals(reader.hosts.get(root))) throw new GradleException("Application root host " + reader.hosts.get(root) + " does not match the " + getHost().get() + " Gradle plugin");
        return reader;
    }

    @Input public Map<String, String> getRootMetadata() throws IOException {
        var reader = metadata();
        String root = reader.applications.keySet().iterator().next();
        return Map.of("host", getHost().get(), "root-class", root,
                "mode", reader.ordinaryRoots.get(root) ? "application" : "legacy");
    }

    /** Only values written to the public descriptor participate, never the whole process environment. */
    @Input public Map<String, Object> getResolvedDescriptor() throws IOException {
        PluginMetadataReader reader = metadata();
        var application = reader.applications.entrySet().iterator().next();
        var metadata = application.getValue();
        // Only legacy public descriptor placeholders use this fallback. Never load runtime profiles/imports.
        PaperConfiguration config = LegacyBuildSettings.configuration(Map.of());
        if (metadata.toString().contains("${") || reader.commands.toString().contains("${")
                || (getHost().get().equals("paper") && !getApiVersion().isPresent()
                && metadata.getOrDefault("apiVersion", "").toString().isBlank())) {
            if (getConfigurationFile().isPresent() && Files.exists(getConfigurationFile().get().getAsFile().toPath())) {
                config = PaperConfiguration.of(YamlDocument.read(getConfigurationFile().get().getAsFile().toPath()));
            }
        }
        resolve(metadata, config);
        if (getHost().get().equals("velocity")) return velocity(application.getKey(), metadata, reader.ordinaryRoots.get(application.getKey()));
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("name", required(metadata, "name"));
        output.put("main", reader.ordinaryRoots.get(application.getKey())
                ? "org.blu3fishez.springpaper.bootstrap.SpringPaperApplicationPlugin" : application.getKey());
        output.put("version", getPluginVersion().get());
        String apiVersion = (String) metadata.getOrDefault("apiVersion", "");
        if (getApiVersion().isPresent()) apiVersion = getApiVersion().get();
        if (apiVersion.isBlank()) apiVersion = config.text("spring.paper.minecraft.api-version", config.text("spring.paper.minecraft.version", ""));
        if (apiVersion.isBlank()) throw new GradleException("Paper descriptor requires springPaper.paper.apiVersion (or legacy minecraft.api-version/version)");
        output.put("api-version", apiVersion);
        output.put("load", metadata.getOrDefault("load", "POSTWORLD"));
        for (String key : List.of("prefix", "description", "authors", "website", "depend")) copy(metadata, output, key, key);
        copy(metadata, output, "softDepend", "softdepend");
        Map<String, Object> commands = new LinkedHashMap<>();
        Map<String, Object> permissions = new LinkedHashMap<>();
        PaperConfiguration descriptorConfig = config;
        reader.commands.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Map<String, Object> command = new LinkedHashMap<>();
            Map<String, Object> values = entry.getValue();
            resolve(values, descriptorConfig);
            for (String key : List.of("description", "usage", "aliases", "permission")) copy(values, command, key, key);
            @SuppressWarnings("unchecked") List<String> labels = (List<String>) values.getOrDefault("commands", List.of());
            if (labels.isEmpty()) throw new GradleException(entry.getKey() + ": command labels are required");
            for (String label : labels) {
                if (label.isBlank() || label.contains(" ") || label.startsWith("/")) throw new GradleException("Invalid command label: " + label);
                Object prior = commands.putIfAbsent(label, command);
                if (prior != null && !prior.equals(command)) throw new GradleException("Conflicting command metadata: " + label);
            }
            String permission = (String) values.getOrDefault("permission", "");
            if (!permission.isBlank()) {
                String permissionDefault = (String) values.getOrDefault("permissionDefault", "op");
                if (!List.of("op", "not op", "true", "false").contains(permissionDefault)) throw new GradleException("Invalid permissionDefault: " + permissionDefault);
                Map<String, Object> definition = Map.of("default", permissionDefault);
                Object prior = permissions.putIfAbsent(permission, definition);
                if (prior != null && !prior.equals(definition)) throw new GradleException("Conflicting permission: " + permission);
            }
        });
        PluginPermissions.merge(metadata.getOrDefault("permissions", List.of()), permissions);
        if (!commands.isEmpty()) output.put("commands", commands);
        if (!permissions.isEmpty()) output.put("permissions", permissions);
        return output;
    }
    private Map<String, Object> velocity(String root, Map<String, Object> metadata, boolean ordinary) {
        Map<String, Object> result = new LinkedHashMap<>();
        String id = required(metadata, "id");
        if (!id.matches("[a-z][a-z0-9_-]{0,63}")) throw new GradleException("Invalid Velocity plugin id: " + id);
        result.put("id", id);
        result.put("name", required(metadata, "name"));
        result.put("version", getPluginVersion().get());
        result.put("main", ordinary ? "org.blu3fishez.springpaper.velocity.bootstrap.SpringVelocityApplicationPlugin" : root);
        for (String key : List.of("description", "url", "authors", "dependencies")) copy(metadata, result, key, key);
        return result;
    }
    @TaskAction public void generate() throws IOException {
        Map<String, Object> output = getResolvedDescriptor();
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        var directory = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(directory);
        String descriptor = getHost().get().equals("paper") ? "plugin.yml" : "velocity-plugin.json";
        Files.writeString(directory.resolve(descriptor), getHost().get().equals("paper")
                ? new Yaml(options).dump(output) : JsonOutput.prettyPrint(JsonOutput.toJson(output)) + "\n");
        Path metadata = directory.resolve("META-INF/spring-paper/application.properties");
        Files.createDirectories(metadata.getParent());
        StringBuilder content = new StringBuilder();
        getRootMetadata().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> content.append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
        Files.writeString(metadata, content);
    }
    private static String required(Map<String, Object> values, String key) {
        String value = (String) values.getOrDefault(key, "");
        if (value.isBlank()) throw new GradleException("Application " + key + " is required");
        return value;
    }
    private static void resolve(Map<String, Object> metadata, PaperConfiguration config) {
        metadata.replaceAll((key, value) -> resolved(value, config));
    }
    private static Object resolved(Object value, PaperConfiguration config) {
        if (value instanceof String text) return config.environment().resolveRequiredPlaceholders(text);
        if (value instanceof List<?> list) return list.stream().map(element -> resolved(element, config)).toList();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key.toString(), resolved(item, config)));
            return result;
        }
        return value;
    }
    private static void copy(Map<String, Object> source, Map<String, Object> target, String from, String to) {
        Object value = source.get(from);
        if (value == null || value.equals("") || value.equals(List.of())) return;
        target.put(to, value);
    }
}
