package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
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
    @Test void preservesNestedAdditionalPermissionsAndRejectsConflictingDefaults() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/Permissions", null, "org/bukkit/plugin/java/JavaPlugin", null);
        var application = writer.visitAnnotation("Lorg/blu3fishez/springpaper/annotation/SpringPaperPluginApplication;", true);
        var permissions = application.visitArray("permissions");
        var permission = permissions.visitAnnotation(null, "Lorg/blu3fishez/springpaper/annotation/PaperPermission;");
        permission.visit("name", "plugin.command.admin");
        permission.visit("description", "Operator subcommand");
        permission.visitEnum("defaultValue", "Lorg/bukkit/permissions/PermissionDefault;", "OP");
        permission.visitEnd(); permissions.visitEnd(); application.visitEnd(); writer.visitEnd();
        Files.write(directory.resolve("Permissions.class"), writer.toByteArray());
        var reader = new PluginMetadataReader(); reader.read(directory, true);
        var result = new LinkedHashMap<String, Object>();
        PluginPermissions.merge(reader.applications.get("example.Permissions").get("permissions"), result);
        assertEquals(Map.of("plugin.command.admin", Map.of("default", "op", "description", "Operator subcommand")), result);
        assertThrows(IllegalArgumentException.class, () -> PluginPermissions.merge(
                List.of(Map.of("name", "plugin.command.admin", "defaultValue", "TRUE")), result));
    }
    @Test void retainsDistinctMetadataForRepeatedCommandsOnOneExecutor() throws Exception {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/SharedCommand", null, "java/lang/Object", null);
        var container = writer.visitAnnotation("Lorg/blu3fishez/springpaper/annotation/PaperCommandExecutors;", true);
        var array = container.visitArray("value");
        for (String name : List.of("reset", "reset-all")) {
            var command = array.visitAnnotation(null, "Lorg/blu3fishez/springpaper/annotation/PaperCommandExecutor;");
            var labels = command.visitArray("commands"); labels.visit(null, name); labels.visitEnd();
            command.visit("usage", "/" + name);
            command.visit("permission", "plugin." + name);
            command.visitEnd();
        }
        array.visitEnd(); container.visitEnd(); writer.visitEnd();
        Files.write(directory.resolve("SharedCommand.class"), writer.toByteArray());
        var reader = new PluginMetadataReader(); reader.read(directory, true);
        assertEquals(List.of("/reset", "/reset-all"), reader.commands.values().stream().map(value -> value.get("usage")).toList());
        assertEquals(List.of("plugin.reset", "plugin.reset-all"), reader.commands.values().stream().map(value -> value.get("permission")).toList());
    }
}
