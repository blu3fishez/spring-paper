package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class SpringMetadataPackagingTest {
    private static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    private static final String EXTENSION_IMPORTS = "META-INF/spring/example.Extension.imports";
    @TempDir Path directory;

    @Test void preservesSpringMetadataFromEveryDependencyAndExistingPackagingRules() throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name = 'metadata-fixture'\n");
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'org.blu3fishez.spring-paper' }
                version = '1.0'
                repositories {
                    clear()
                    maven { url = uri('repository') }
                }
                dependencies {
                    implementation 'fixtures:first:1.0'
                    implementation 'fixtures:second:1.0'
                    implementation 'org.slf4j:slf4j-api:1.0'
                }
                sourceSets.main.output.classesDirs.from(layout.projectDirectory.dir('classes'))
                """);
        Path resources = Files.createDirectories(directory.resolve("src/main/resources"));
        Files.writeString(resources.resolve("application.yml"), """
                spring:
                  paper:
                    minecraft:
                      version: '26.3'
                      api-coordinate: 'fixture'
                      java-version: %d
                """.formatted(Runtime.version().feature()));
        Path classes = Files.createDirectories(directory.resolve("classes/example"));
        Files.write(classes.resolve("Plugin.class"), type("example/Plugin", true));
        var version = new Properties();
        try (var input = SpringPaperPlugin.class.getResourceAsStream("/spring-paper-version.properties")) {
            assertNotNull(input);
            version.load(input);
        }
        artifact("org.blu3fishez.springpaper", "spring-paper-core", version.getProperty("version"), Map.of());
        artifact("io.papermc.paper", "paper-api", "fixture", Map.of(
                "org/bukkit/PaperApiFixture.class", type("org/bukkit/PaperApiFixture", false)));
        artifact("org.slf4j", "slf4j-api", "1.0", Map.of(
                "org/slf4j/Slf4jFixture.class", type("org/slf4j/Slf4jFixture", false)));
        artifact("fixtures", "first", "1.0", Map.of(
                AUTO_CONFIGURATION_IMPORTS, text("# first fixture\nexample.FirstAutoConfiguration\nexample.SharedAutoConfiguration"),
                EXTENSION_IMPORTS, text("example.FirstExtension"),
                "META-INF/spring.factories", text(String.join("\n",
                        "example.Factory=example.DiscardedFactory",
                        "example.Factory = \\",
                        "  example.FirstFactory,\\",
                        "  example.AdditionalFactory",
                        "example.Escaped\\ Key=example.Value\\u0031",
                        "example.FirstOnly=example.FirstOnlyFactory")),
                "META-INF/services/example.Service", text("example.FirstService"),
                "META-INF/fixture.properties", text("raw = value"),
                "META-INF/springXfactories", text("not a Spring factories file\n")));
        artifact("fixtures", "second", "1.0", Map.of(
                AUTO_CONFIGURATION_IMPORTS, text("example.SecondAutoConfiguration\n"),
                EXTENSION_IMPORTS, text("example.SecondExtension\n"),
                "META-INF/spring.factories", text(String.join("\n",
                        "example.Factory: example.SecondFactory",
                        "example.Escaped\\u0020Key=example.Value2",
                        "example.SecondOnly=example.SecondOnlyFactory")),
                "META-INF/services/example.Service", text("example.SecondService")));

        assertEquals(TaskOutcome.SUCCESS, packagePlugin().task(":shadowJar").getOutcome());
        assertPackagedMetadata();
        var cached = packagePlugin();
        assertTrue(cached.getOutput().contains("Reusing configuration cache."), cached.getOutput());
        assertEquals(TaskOutcome.SUCCESS, cached.task(":shadowJar").getOutcome());
        assertPackagedMetadata();
    }

    private org.gradle.testkit.runner.BuildResult packagePlugin() {
        return GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath()
                .withArguments("shadowJar", "--offline", "--max-workers=1", "--console=plain",
                        "--configuration-cache", "--rerun-tasks")
                .build();
    }

    private void assertPackagedMetadata() throws Exception {
        try (var jar = new JarFile(directory.resolve("build/libs/metadata-fixture-1.0.jar").toFile())) {
            assertEquals(List.of("example.FirstAutoConfiguration", "example.SharedAutoConfiguration", "example.SecondAutoConfiguration"),
                    lines(jar, AUTO_CONFIGURATION_IMPORTS));
            assertEquals(List.of("example.FirstExtension", "example.SecondExtension"), lines(jar, EXTENSION_IMPORTS));
            var factories = new Properties();
            try (var input = jar.getInputStream(jar.getJarEntry("META-INF/spring.factories"))) { factories.load(input); }
            assertEquals(Set.of("example.FirstFactory", "example.AdditionalFactory", "example.SecondFactory"),
                    values(factories, "example.Factory"));
            assertEquals(Set.of("example.Value1", "example.Value2"), values(factories, "example.Escaped Key"));
            assertEquals("example.FirstOnlyFactory", factories.getProperty("example.FirstOnly"));
            assertEquals("example.SecondOnlyFactory", factories.getProperty("example.SecondOnly"));
            assertEquals(List.of("example.FirstService", "example.SecondService"), lines(jar, "META-INF/services/example.Service"));
            assertEquals("raw = value", resource(jar, "META-INF/fixture.properties"));
            assertEquals("not a Spring factories file\n", resource(jar, "META-INF/springXfactories"));
            for (String path : List.of(AUTO_CONFIGURATION_IMPORTS, EXTENSION_IMPORTS, "META-INF/spring.factories")) {
                assertEquals(1, jar.stream().filter(entry -> entry.getName().equals(path)).count(), path);
            }
            assertNotNull(jar.getJarEntry("example/Plugin.class"));
            assertNotNull(jar.getJarEntry("plugin.yml"));
            assertNull(jar.getJarEntry("org/bukkit/PaperApiFixture.class"));
            assertNull(jar.getJarEntry("org/slf4j/Slf4jFixture.class"));
        }
    }

    private List<String> lines(JarFile jar, String path) throws Exception {
        return resource(jar, path).lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
    }

    private String resource(JarFile jar, String path) throws Exception {
        var entry = jar.getJarEntry(path);
        assertNotNull(entry, path);
        try (var input = jar.getInputStream(entry)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private Set<String> values(Properties properties, String key) {
        return Arrays.stream(properties.getProperty(key).split(",")).map(String::trim).collect(Collectors.toSet());
    }

    private void artifact(String group, String name, String version, Map<String, byte[]> entries) throws Exception {
        Path artifact = Files.createDirectories(directory.resolve("repository").resolve(group.replace('.', '/')).resolve(name).resolve(version));
        Files.writeString(artifact.resolve(name + "-" + version + ".pom"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version></project>
                """.formatted(group, name, version));
        try (var output = new JarOutputStream(Files.newOutputStream(artifact.resolve(name + "-" + version + ".jar")))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private byte[] text(String value) { return value.getBytes(StandardCharsets.ISO_8859_1); }

    private byte[] type(String name, boolean application) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        if (application) {
            var metadata = writer.visitAnnotation("Lorg/blu3fishez/springpaper/annotation/SpringPaperPluginApplication;", true);
            metadata.visit("name", "MetadataFixture");
            metadata.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
