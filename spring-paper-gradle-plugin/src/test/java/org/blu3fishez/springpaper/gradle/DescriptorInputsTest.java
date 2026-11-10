package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class DescriptorInputsTest {
    @TempDir Path directory;

    @Test void changedResolvedMetadataInvalidatesTheDescriptorWithoutFingerprintingUnrelatedProperties() throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), """
                rootProject.name = 'descriptor-inputs'
                buildCache { local { directory = new File(settingsDir, 'build-cache') } }
                """);
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'org.blu3fishez.spring-paper' apply false }
                tasks.register('descriptor', org.blu3fishez.springpaper.gradle.GeneratePluginDescriptor) {
                    classes.from(layout.projectDirectory.dir('classes'))
                    configurationFile = layout.projectDirectory.file('application.yml')
                    pluginVersion = '1.0'
                    outputDirectory = layout.buildDirectory.dir('descriptor')
                }
                """);
        Files.writeString(directory.resolve("application.yml"), """
                spring:
                  paper:
                    minecraft:
                      version: '26.3'
                """);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/Plugin", null, "java/lang/Object", null);
        var metadata = writer.visitAnnotation(
                "Lorg/blu3fishez/springpaper/annotation/SpringPaperPluginApplication;", true);
        metadata.visit("name", "${spring.paper.audit.plugin-name}");
        metadata.visitEnd();
        writer.visitEnd();
        Files.write(classes.resolve("Plugin.class"), writer.toByteArray());

        assertEquals(TaskOutcome.SUCCESS, generate("First", "unused-one"));
        assertTrue(Files.readString(descriptor()).contains("name: First"));
        assertEquals(TaskOutcome.SUCCESS, generate("Second", "unused-one"));
        assertTrue(Files.readString(descriptor()).contains("name: Second"));
        assertEquals(TaskOutcome.UP_TO_DATE, generate("Second", "unused-two"));
        Files.delete(descriptor());
        assertEquals(TaskOutcome.FROM_CACHE, generate("Second", "unused-three"));
        assertTrue(Files.readString(descriptor()).contains("name: Second"));
    }

    private TaskOutcome generate(String name, String unused) {
        return GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
                .withArguments("descriptor", "-Dspring.paper.audit.plugin-name=" + name,
                        "-Dspring.paper.audit.unused=" + unused, "--max-workers=1", "--console=plain",
                        "--configuration-cache", "--build-cache")
                .build().task(":descriptor").getOutcome();
    }
    private Path descriptor() { return directory.resolve("build/descriptor/plugin.yml"); }
}
