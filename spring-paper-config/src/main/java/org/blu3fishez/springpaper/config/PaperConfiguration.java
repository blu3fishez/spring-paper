package org.blu3fishez.springpaper.config;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.ConfigurableEnvironment;

/** Resolved application.yml values with standard Spring system-property/environment precedence. */
public final class PaperConfiguration {
    private final ConfigurableEnvironment environment;

    public PaperConfiguration(ConfigurableEnvironment environment) { this.environment = environment; }
    public static PaperConfiguration of(YamlDocument document) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("springPaperYaml", document.values()));
        return new PaperConfiguration(environment);
    }
    public ConfigurableEnvironment environment() { return environment; }
    public String text(String key, String fallback) { return environment.getProperty(key, fallback); }
    public String required(String key) {
        String result = environment.getRequiredProperty(key);
        if (result.isBlank()) throw new IllegalArgumentException(key + " must not be blank");
        return result;
    }
    public boolean enabled(String key) { return environment.getProperty(key, Boolean.class, false); }
    public int integer(String key, int fallback) { return environment.getProperty(key, Integer.class, fallback); }
    public List<String> strings(String key) {
        String[] result = environment.getProperty(key, String[].class, new String[0]);
        return java.util.Arrays.stream(result).map(environment::resolveRequiredPlaceholders).toList();
    }
    public Map<String, String> children(String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        String start = prefix + ".";
        for (var source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String key : enumerable.getPropertyNames()) {
                if (key.startsWith(start)) result.put(key.substring(start.length()), environment.getProperty(key));
            }
        }
        return Map.copyOf(result);
    }
}
