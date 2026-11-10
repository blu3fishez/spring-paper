package org.blu3fishez.springpaper.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import org.blu3fishez.springpaper.key.PaperKeys;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class PluginUtilitiesTest {
    @Test void keysPreserveStoredNamespacesAndNativeNormalizationAndParsing() {
        var keys = new PaperKeys("simpleextraction");
        assertEquals("simpleextraction:elite_equipment/schema", keys.key("Elite_Equipment/Schema").toString());
        assertEquals("simpleextraction:marker", keys.parse("marker").toString());
        assertEquals("simpleextraction:marker", keys.parse(":marker").toString());
        assertEquals("minecraft:stone", keys.parse("minecraft:stone").toString());
        assertNull(keys.parse("BadNamespace:stone"));
        assertNull(keys.parse("marker with spaces"));
        assertNull(keys.parse(""));
    }
    @Test void configurationReadsTheCurrentHostObjectAndSavesThroughItsOwner() {
        var plugin = mock(JavaPlugin.class);
        var initial = new YamlConfiguration();
        var reloaded = new YamlConfiguration();
        when(plugin.getConfig()).thenReturn(initial, reloaded);
        var configuration = new PaperBukkitConfiguration(plugin);
        assertSame(initial, configuration.values());
        assertSame(reloaded, configuration.values());
        configuration.save();
        verify(plugin).saveConfig();
    }
    @Test void pluginIdentityCapturesTheHostPathNamespaceAndLoader() {
        var plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("Example");
        when(plugin.namespace()).thenReturn("example");
        when(plugin.getDataFolder()).thenReturn(Path.of("build", "example-data").toFile());
        var info = PaperPluginInfo.capture(plugin);
        assertEquals("Example", info.name());
        assertEquals("example", info.namespace());
        assertEquals(Path.of("build", "example-data"), info.dataDirectory());
        assertSame(plugin.getClass().getClassLoader(), info.classLoader());
    }
}
