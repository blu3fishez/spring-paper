package org.blu3fishez.springpaper.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class ConfigDataSettingsTest {
    @TempDir Path directory;

    @Test void usesOnlyOwnArtifactAndDataDirectoryWithBootProfilePrecedenceAndRelativeImports() throws Exception {
        Path artifact = jar(Map.of("application.yml", "spring.profiles.active: demo\nvalue: packaged-base\n",
                "application-demo.yml", "value: packaged-profile\n"));
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("application.yml"), "value: external-base\nspring.config.import: extra.yml\n");
        Files.writeString(data.resolve("extra.yml"), "imported: '${value}'\n");
        Path unrelated = Files.createDirectory(directory.resolve("unrelated"));
        Files.writeString(unrelated.resolve("application.yml"), "value: classpath-foreign\nforeign: must-not-load\n");
        try (var loader = new URLClassLoader(new URL[] { unrelated.toUri().toURL() }, getClass().getClassLoader())) {
            var environment = ConfigDataSettings.load(artifact, data, loader);
            assertEquals("external-base", environment.getProperty("value"));
            assertEquals("external-base", environment.getProperty("imported"));
            assertNull(environment.getProperty("foreign"));
            Files.writeString(data.resolve("application-demo.yml"), "value: external-profile\n");
            assertEquals("external-profile", ConfigDataSettings.load(artifact, data, loader).getProperty("value"));
        }
    }

    @Test void bindsOnlyNativePrefixesAndPreservesBooleanNumberListAndLiteralDottedMapKeys() throws Exception {
        Path artifact = jar(Map.of("application.yml", """
                spring:
                  paper.velocity:
                    enabled: true
                    online-mode: false
                    secret-file: '${probe.secret-file}'
                  velocity.server:
                    bind: '[::1]:25577'
                    online-mode: true
                    compression-threshold: 256
                    servers:
                      '[lobby.eu]': '127.0.0.1:25565'
                      try: [lobby.eu]
                    forced-hosts:
                      '[play.example.org]': [lobby.eu]
                probe.secret-file: /private/shared.secret
                unrelated.database.password: must-stay-private
                """));
        var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader());
        Map<String, Object> settings = ConfigDataSettings.launchSettings(environment);
        Map<?, ?> paper = (Map<?, ?>) settings.get("paper-velocity");
        assertEquals(true, paper.get("enabled"));
        assertEquals(false, paper.get("online-mode"));
        assertEquals("/private/shared.secret", paper.get("secret-file"));
        Map<?, ?> velocity = (Map<?, ?>) settings.get("velocity-server");
        assertEquals(true, velocity.get("online-mode"));
        assertEquals(256, velocity.get("compression-threshold"));
        Map<?, ?> servers = (Map<?, ?>) velocity.get("servers");
        assertEquals("127.0.0.1:25565", servers.get("lobby.eu"));
        assertEquals(List.of("lobby.eu"), servers.get("try"));
        assertEquals(List.of("lobby.eu"), ((Map<?, ?>) velocity.get("forced-hosts")).get("play.example.org"));
        String yaml = new Yaml().dump(settings);
        assertFalse(yaml.contains("must-stay-private"));
        assertEquals(settings, new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml));
        Path output = directory.resolve("output.yml");
        ConfigDataProbe.main(new String[] { "--jar", artifact.toString(), "--data-dir", directory.resolve("data").toString(),
                "--output", output.toString() });
        assertEquals(settings, new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(output)));
    }

    @Test void disabledOrAbsentPaperForwardingDoesNotResolveAnUnusedSecretPlaceholder() throws Exception {
        for (String enabled : List.of("enabled: false\n", "")) {
            Path artifact = jar(Map.of("application.yml", "spring.paper.velocity:\n  "
                    + enabled.replace("\n", "\n  ") + "secret-file: '${UNSET_BOOT_PROBE_SECRET_FILE}'\n"));
            var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader());
            assertEquals(Map.of("enabled", false), ConfigDataSettings.launchSettings(environment).get("paper-velocity"));
        }
    }

    @Test void resolvesJsonAndExplicitConfigLocationBeforeLoadingWithoutStartingAContext() throws Exception {
        Path artifact = jar(Map.of("application.yml", "value: own-artifact\n"));
        Path selected = directory.resolve("selected.yml");
        Files.writeString(selected, "value: selected-location\n");
        var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader(),
                "--spring.config.location=" + selected.toUri(), "--spring.application.json={\"json-probe\":\"present\"}");
        assertEquals("selected-location", environment.getProperty("value"));
        assertEquals("present", environment.getProperty("json-probe"));
    }

    private Path jar(Map<String, String> entries) throws Exception {
        Path artifact = directory.resolve("application.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return artifact;
    }
}
