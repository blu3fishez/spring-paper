package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.util.Map;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.config.YamlDocument;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ValueSource;
import org.gradle.api.provider.ValueSourceParameters;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Optional legacy build inputs, evaluated only by the task that consumes them. */
public abstract class LegacyBuildSettings implements ValueSource<Map<String, Object>, LegacyBuildSettings.Parameters> {
    public interface Parameters extends ValueSourceParameters {
        RegularFileProperty getConfigurationFile();
    }
    @Override public Map<String, Object> obtain() {
        var file = getParameters().getConfigurationFile().get().getAsFile().toPath();
        if (!java.nio.file.Files.exists(file)) return Map.of();
        try { return YamlDocument.read(file).values(); }
        catch (IOException | RuntimeException error) { throw new GradleException("Cannot read legacy build configuration " + file, error); }
    }
    static PaperConfiguration configuration(Map<String, Object> values) {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("legacyBuildYaml", values));
        return new PaperConfiguration(environment);
    }
    static String coordinate(String value, String groupArtifact, String diagnostic) {
        if (value == null || value.isBlank()) throw new GradleException(diagnostic);
        String result = value.contains(":") ? value : groupArtifact + ":" + value;
        String[] parts = result.split(":", -1);
        if (parts.length != 3 || java.util.Arrays.stream(parts).anyMatch(String::isBlank)) {
            throw new GradleException("apiCoordinate must be a full group:artifact:version coordinate");
        }
        return result;
    }
}
