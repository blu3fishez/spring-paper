package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class PluginMetadataReaderTest {
    @TempDir Path directory;
    private byte[] type(String name, String annotation, String property, String value, boolean array) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name, null, "org/bukkit/plugin/java/JavaPlugin", null);
        var metadata = writer.visitAnnotation("Lorg/blu3fishez/springpaper/annotation/" + annotation + ";", true);
        if (array) { var values = metadata.visitArray(property); values.visit(null, value); values.visitEnd(); }
        else metadata.visit(property, value);
        metadata.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    @Test void readsJava25MetadataWithoutLoadingPaperAndFindsCommandsInFeatureJars() throws Exception {
        Path classes = directory.resolve("classes"); Files.createDirectories(classes);
        Files.write(classes.resolve("Plugin.class"), type("example/Plugin", "SpringPaperPluginApplication", "name", "Example", false));
        Path feature = directory.resolve("feature.jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(feature))) {
            output.putNextEntry(new ZipEntry("example/Command.class"));
            output.write(type("example/Command", "PaperCommandExecutor", "commands", "guide", true));
            output.closeEntry();
            output.putNextEntry(new ZipEntry("example/OtherPlugin.class"));
            output.write(type("example/OtherPlugin", "SpringPaperPluginApplication", "name", "Dependency", false));
        }
        var reader = new PluginMetadataReader(); reader.read(classes, true); reader.read(feature, false);
        assertEquals(List.of("example.Plugin"), reader.applications.keySet().stream().toList());
        assertEquals(List.of("guide"), reader.commands.get("example.Command").get("commands"));
    }
}
