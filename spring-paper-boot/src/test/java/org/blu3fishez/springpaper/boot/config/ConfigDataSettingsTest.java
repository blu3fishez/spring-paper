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
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
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
            var projected = ConfigDataSettings.launchSettings(environment, List.of("spring.paper.velocity.enabled",
                    "spring.paper.velocity.online-mode", "spring.paper.velocity.secret-file"));
            assertFalse(((Map<?, ?>) projected.get("native-field-values")).containsKey("spring.paper.velocity.secret-file"));
            assertFalse(((Map<?, ?>) projected.get("native-field-values")).containsKey("spring.paper.velocity.online-mode"));
        }
        for (String invalid : List.of("not-a-boolean", "1")) {
            Path artifact = jar(Map.of("application.yml", "spring.paper.velocity:\n  enabled: " + invalid
                    + "\n  secret-file: '${UNSET_BOOT_PROBE_SECRET_FILE}'\n"));
            var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader());
            var projected = ConfigDataSettings.launchSettings(environment, List.of("spring.paper.velocity.enabled",
                    "spring.paper.velocity.secret-file"));
            assertEquals(Map.of("enabled", false), projected.get("paper-velocity"));
            assertEquals(Map.of("spring.paper.velocity.enabled", invalid.equals("1") ? 1 : invalid), projected.get("native-field-values"));
        }
    }

    @Test void canonicalScalarProjectionUsesSystemPropertiesThenEnvThenYamlWithoutReorderingRawAliases() throws Exception {
        Path artifact = jar(Map.of("application.yml", """
                spring.velocity.server:
                  online-mode: 1
                  show-max-players: 37.0
                  force-key-authentication: true
                  motd: '${probe.motd}'
                  servers:
                    '[lobby.eu]': '127.0.0.1:25565'
                    try: [lobby.eu]
                  forced-hosts:
                    '[play.example.org]': [lobby.eu]
                probe.motd: '00042'
                unrelated.password: must-not-export
                """));
        var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader());
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of()));
        var fields = List.of("spring.velocity.server.online-mode", "spring.velocity.server.show-max-players",
                "spring.velocity.server.motd", "spring.velocity.server.force-key-authentication");
        assertEquals(Map.of(fields.get(0), 1, fields.get(1), 37.0, fields.get(2), "00042", fields.get(3), true),
                ConfigDataSettings.launchSettings(environment, fields).get("native-field-values"));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "SPRING_VELOCITY_SERVER_ONLINEMODE", "false", "SPRING_VELOCITY_SERVER_SHOWMAXPLAYERS", "31")));
        assertEquals(Map.of(fields.get(0), "false", fields.get(1), "31", fields.get(2), "00042", fields.get(3), true),
                ConfigDataSettings.launchSettings(environment, fields).get("native-field-values"));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of(
                        fields.get(0), "true", fields.get(1), "53")));
        var settings = ConfigDataSettings.launchSettings(environment, fields);
        assertEquals(Map.of(fields.get(0), "true", fields.get(1), "53", fields.get(2), "00042", fields.get(3), true), settings.get("native-field-values"));
        var nativeValues = (Map<?, ?>) settings.get("velocity-server");
        assertEquals("00042", nativeValues.get("motd"));
        assertEquals(Map.of("lobby.eu", "127.0.0.1:25565", "try", List.of("lobby.eu")), nativeValues.get("servers"));
        assertEquals(Map.of("play.example.org", List.of("lobby.eu")), nativeValues.get("forced-hosts"));
        assertFalse(new Yaml().dump(settings).contains("must-not-export"));
    }

    @Test void helperProjectsRequestedNativeScalarsAndRejectsFieldsOutsideTheTwoPrefixes() throws Exception {
        Path artifact = jar(Map.of("application.properties", "spring.velocity.server.show-max-players=57\n"
                + "spring.velocity.server.packet-limiter.interval=2000\n"));
        Path output = directory.resolve("projected.yml");
        ConfigDataProbe.main(new String[] { "--jar", artifact.toString(), "--data-dir", directory.resolve("data").toString(),
                "--output", output.toString(), "--native-field", "spring.velocity.server.show-max-players",
                "--native-map", "spring.velocity.server.packet-limiter" });
        Map<?, ?> result = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(output));
        assertEquals(Map.of("spring.velocity.server.show-max-players", "57"), result.get("native-field-values"));
        assertEquals(Map.of("spring.velocity.server.packet-limiter", Map.of("interval", "2000")), result.get("native-map-values"));
        var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader());
        assertThrows(IllegalArgumentException.class,
                () -> ConfigDataSettings.launchSettings(environment, List.of("unrelated.password")));
        assertThrows(IllegalArgumentException.class,
                () -> ConfigDataSettings.launchSettings(environment, List.of("spring.velocity.server.servers[0]")));
    }

    @Test void canonicalContainerProjectionResolvesEnvAliasesBeforePreservingDynamicLiteralKeysAndLists() throws Exception {
        Path artifact = jar(Map.of("application.yml", """
                spring.velocity.server:
                  forced-hosts:
                    '[play.example.org]': [lobby.eu]
                    localhost: [yaml-lobby]
                  packet-limiter:
                    interval: 2000
                """));
        var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader());
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of()));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "SPRING_VELOCITY_SERVER_FORCEDHOSTS_LOCALHOST_0", "env-lobby",
                        "SPRING_VELOCITY_SERVER_PACKETLIMITER_INTERVAL", "3000")));
        var groups = List.of("spring.velocity.server.forced-hosts", "spring.velocity.server.packet-limiter");
        var projected = (Map<?, ?>) ConfigDataSettings.launchSettings(environment, List.of(), groups).get("native-map-values");
        assertEquals(Map.of("play.example.org", List.of("lobby.eu"), "localhost", List.of("env-lobby")), projected.get(groups.get(0)));
        assertEquals(Map.of("interval", "3000"), projected.get(groups.get(1)));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of(
                        groups.get(0) + ".localhost[0]", "system-lobby", groups.get(1) + ".interval", "4000")));
        projected = (Map<?, ?>) ConfigDataSettings.launchSettings(environment, List.of(), groups).get("native-map-values");
        assertEquals(Map.of("play.example.org", List.of("lobby.eu"), "localhost", List.of("system-lobby")), projected.get(groups.get(0)));
        assertEquals(Map.of("interval", "4000"), projected.get(groups.get(1)));
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
        Path artifact = Files.createTempFile(directory, "application-", ".jar");
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
