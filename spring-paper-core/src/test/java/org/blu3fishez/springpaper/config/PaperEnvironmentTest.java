package org.blu3fishez.springpaper.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperEnvironmentTest {
    @TempDir Path directory;
    private JavaPlugin plugin(Map<String, String> resources) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource(anyString())).thenAnswer(input -> {
            String text = resources.get(input.getArgument(0, String.class));
            return text == null ? null : new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        });
        return plugin;
    }
    @Test void overlaysExternalProfileAndActivatesSpringProfileWithYamlExtension() throws Exception {
        Files.writeString(directory.resolve("application.yaml"), "message: external-base\n");
        Files.writeString(directory.resolve("application-dungeon.yaml"), "message: external-profile\n");
        var plugin = plugin(Map.of("application.yaml", "spring.profiles.active: dungeon\nmessage: default\n",
                "application-dungeon.yaml", "message: jar-profile\n"));
        var environment = PaperEnvironment.load(plugin, "application.yaml");
        assertEquals("external-profile", environment.getProperty("message"));
        assertArrayEquals(new String[] { "dungeon" }, environment.getActiveProfiles());
    }
    @Test void rejectsProfileFilesThatChangeTheActiveProfileSet() {
        var plugin = plugin(Map.of("application.yml", "spring.profiles.active: dungeon\n",
                "application-dungeon.yml", "spring.profiles.active: skyblock\n"));
        assertThrows(IllegalArgumentException.class, () -> PaperEnvironment.load(plugin, "application.yml"));
    }
}
