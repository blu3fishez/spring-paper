package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
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
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

@CacheableTask
public abstract class GeneratePluginDescriptor extends DefaultTask {
    @Classpath public abstract ConfigurableFileCollection getClasses();
    @Classpath public abstract ConfigurableFileCollection getLibraries();
    @InputFile @PathSensitive(PathSensitivity.RELATIVE) public abstract RegularFileProperty getConfigurationFile();
    @Input public abstract Property<String> getPluginVersion();
    @OutputDirectory public abstract DirectoryProperty getOutputDirectory();

    @TaskAction public void generate() throws IOException {
        PluginMetadataReader reader = new PluginMetadataReader();
        for (var file : getClasses()) reader.read(file.toPath(), true);
        for (var file : getLibraries()) reader.read(file.toPath(), false);
        if (reader.applications.size() != 1) throw new GradleException("Exactly one @SpringPaperPluginApplication entrypoint is required; found " + reader.applications.keySet());
        var application = reader.applications.entrySet().iterator().next();
        var metadata = application.getValue();
        PaperConfiguration config = PaperConfiguration.of(YamlDocument.read(getConfigurationFile().get().getAsFile().toPath()));
        resolve(metadata, config);
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("name", required(metadata, "name"));
        output.put("main", application.getKey());
        output.put("version", getPluginVersion().get());
        String apiVersion = (String) metadata.getOrDefault("apiVersion", "");
        if (apiVersion.isBlank()) apiVersion = config.text("spring.paper.minecraft.api-version", config.required("spring.paper.minecraft.version"));
        output.put("api-version", apiVersion);
        output.put("load", metadata.getOrDefault("load", "POSTWORLD"));
        for (String key : List.of("prefix", "description", "authors", "website", "depend")) copy(metadata, output, key, key);
        copy(metadata, output, "softDepend", "softdepend");
        Map<String, Object> commands = new LinkedHashMap<>();
        Map<String, Object> permissions = new LinkedHashMap<>();
        reader.commands.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            Map<String, Object> command = new LinkedHashMap<>();
            Map<String, Object> values = entry.getValue();
            resolve(values, config);
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
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        var directory = getOutputDirectory().get().getAsFile().toPath();
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("plugin.yml"), new Yaml(options).dump(output));
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
