package org.blu3fishez.springpaper.config;

import lombok.Getter;
import lombok.experimental.Accessors;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Safe YAML data shared by the runtime and development Gradle plugin. */
public final class YamlDocument {
    @Getter
    @Accessors(fluent = true, chain = false)
    private final Map<String, Object> values;

    private YamlDocument(Map<String, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static YamlDocument empty() { return new YamlDocument(Map.of()); }

    public static YamlDocument read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) { return read(input); }
    }

    public static YamlDocument read(InputStream input) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded = new Yaml(new SafeConstructor(options)).load(input);
        if (loaded == null) return empty();
        if (!(loaded instanceof Map<?, ?> map)) throw new IllegalArgumentException("YAML root must be a mapping");
        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten("", map, flattened);
        return new YamlDocument(flattened);
    }

    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> target) {
        source.forEach((key, value) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("YAML keys must be strings");
            String path = prefix.isEmpty() ? name : prefix + "." + name;
            if (value instanceof Map<?, ?> nested) flatten(path, nested, target);
            else if (value != null) target.put(path, value instanceof List<?> list ? List.copyOf(list) : value);
        });
    }

    public YamlDocument overlay(YamlDocument overrides) {
        Map<String, Object> result = new LinkedHashMap<>(values);
        result.putAll(overrides.values);
        return new YamlDocument(result);
    }

    public String text(String key, String fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            throw new IllegalArgumentException(key + " must be a scalar");
        }
        return value.toString();
    }

    public String required(String key) {
        String value = text(key, "");
        if (value.isBlank()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    public boolean bool(String key, boolean fallback) {
        String value = text(key, Boolean.toString(fallback));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw new IllegalArgumentException(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    public int integer(String key, int fallback) {
        try { return Integer.parseInt(text(key, Integer.toString(fallback))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer", e); }
    }

    public List<String> strings(String key) {
        Object value = values.get(key);
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list.stream().map(Object::toString).toList();
        List<String> result = new ArrayList<>();
        for (String entry : value.toString().split(",")) if (!entry.isBlank()) result.add(entry.trim());
        return List.copyOf(result);
    }

    public Map<String, String> children(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        String start = prefix + ".";
        values.forEach((key, value) -> {
            if (key.startsWith(start)) result.put(key.substring(start.length()), value.toString());
        });
        return Collections.unmodifiableMap(result);
    }
}
