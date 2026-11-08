package org.blu3fishez.springpaper.config;

import java.io.IOException;
import java.nio.file.Files;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

public final class PaperEnvironment {
    private PaperEnvironment() {}

    public static StandardEnvironment load(JavaPlugin plugin, String resource) throws IOException {
        if (resource.isBlank() || resource.startsWith("/") || resource.contains("..")) {
            throw new IllegalArgumentException("Configuration must be a relative plugin resource");
        }
        StandardEnvironment environment = new StandardEnvironment();
        YamlDocument document = read(plugin, resource);
        environment.getPropertySources().addLast(new MapPropertySource("springPaperApplication", document.values()));
        String profiles = environment.getProperty("spring.profiles.active", "");
        for (String profile : profiles.split(",")) {
            if (profile.isBlank()) continue;
            profile = profile.trim();
            if (!profile.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid profile: " + profile);
            String profileResource = resource.replaceFirst("(\\.ya?ml)$", "-" + profile + "$1");
            if (profileResource.equals(resource)) throw new IllegalArgumentException("Profile configuration requires .yml or .yaml");
            YamlDocument profileDocument = read(plugin, profileResource);
            if (profileDocument.values().containsKey("spring.profiles.active")) {
                throw new IllegalArgumentException(profileResource + " must not redefine spring.profiles.active");
            }
            document = document.overlay(profileDocument);
        }
        environment.getPropertySources().replace("springPaperApplication",
                new MapPropertySource("springPaperApplication", document.values()));
        return environment;
    }

    private static YamlDocument read(JavaPlugin plugin, String resource) throws IOException {
        YamlDocument result = YamlDocument.empty();
        try (var input = plugin.getResource(resource)) {
            if (input != null) result = YamlDocument.read(input);
        }
        var external = plugin.getDataFolder().toPath().resolve(resource);
        if (Files.isRegularFile(external)) result = result.overlay(YamlDocument.read(external));
        return result;
    }
}
