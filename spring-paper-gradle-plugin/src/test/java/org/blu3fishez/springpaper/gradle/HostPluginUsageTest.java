package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class HostPluginUsageTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void helpAndTasksDoNotReadYamlOrResolveAnyTarget(String host) throws Exception {
        fixture(host, false);
        Path resources = Files.createDirectories(directory.resolve("src/main/resources"));
        Files.writeString(resources.resolve("application.yml"), "broken: [ YAML that must never be parsed\n");
        var result = runner("help", "tasks").build();
        assertTrue(result.getOutput().contains("BUILD SUCCESSFUL"));
        assertFalse(Files.exists(directory.resolve("run")));
        assertTrue(runner("help", "tasks").build().getOutput().contains("Reusing configuration cache."));
    }

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void targetOnlyAssembleProducesNativeDescriptorAndOwnRootMetadataWithoutRuntimeYaml(String host) throws Exception {
        fixture(host, true);
        runner("assemble").build();
        assertFalse(Files.exists(directory.resolve("src/main/resources/application.yml")));
        assertFalse(Files.exists(directory.resolve("run")));
        try (var jar = new JarFile(directory.resolve("build/libs/usage-1.0.jar").toFile())) {
            String path = host.equals("paper") ? "plugin.yml" : "velocity-plugin.json";
            String descriptor = new String(jar.getInputStream(jar.getJarEntry(path)).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(descriptor.contains(host.equals("paper")
                    ? "org.blu3fishez.springpaper.bootstrap.SpringPaperApplicationPlugin"
                    : "org.blu3fishez.springpaper.velocity.bootstrap.SpringVelocityApplicationPlugin"), descriptor);
            var metadata = new Properties();
            try (var input = jar.getInputStream(jar.getJarEntry("META-INF/spring-paper/application.properties"))) { metadata.load(input); }
            assertEquals(host, metadata.getProperty("host"));
            assertEquals("example.Application", metadata.getProperty("root-class"));
            assertEquals("application", metadata.getProperty("mode"));
            assertNull(jar.getJarEntry("host/CompileOnly.class"));
            assertFalse(jar.stream().anyMatch(entry -> entry.getName().startsWith("BOOT-INF/")));
        }
        assertTrue(runner("assemble").build().getOutput().contains("Reusing configuration cache."));
    }

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void runWithoutExplicitVersionAndBuildStopsBeforeHelperDownloadOrNativeProcess(String host) throws Exception {
        fixture(host, true);
        var result = runner(host.equals("paper") ? "runServer" : "runVelocity").buildAndFail();
        assertTrue(result.getOutput().contains("Native execution requires explicit"), result.getOutput());
        assertTrue(result.getOutput().contains("no latest version or build is inferred"), result.getOutput());
        assertFalse(Files.exists(directory.resolve("run")));
    }

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void ordinaryApplicationsStillRequireABuildWhenAVersionIsConfigured(String host) throws Exception {
        fixture(host, true);
        Files.writeString(directory.resolve("build.gradle"), host.equals("paper")
                ? "springPaper.development.minecraftVersion = '26.3'\n"
                : "springVelocity.development.version = '4.2.1-SNAPSHOT'\n", java.nio.file.StandardOpenOption.APPEND);
        var result = runner(host.equals("paper") ? "runServer" : "runVelocity").buildAndFail();
        assertTrue(result.getOutput().contains("Native execution requires explicit"), result.getOutput());
        assertTrue(result.getOutput().contains("no latest version or build is inferred"), result.getOutput());
        assertFalse(Files.exists(directory.resolve("run")));
    }

    @Test void rejectsTwoHostsInOneGradleProjectAtApplyTime() throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='usage'\n");
        Files.writeString(directory.resolve("build.gradle"), "plugins { id 'org.blu3fishez.spring-paper'; id 'org.blu3fishez.spring-velocity' }\n");
        assertTrue(runner("help").buildAndFail().getOutput().contains("exactly one application host"));
    }

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void descriptorFallbackReadsOnlyPublicPlaceholdersAndLeavesRuntimeSecretsUnresolved(String host) throws Exception {
        fixture(host, true);
        Files.write(directory.resolve("classes/example/Application.class"), type("example/Application", host, "${public-plugin-name}"));
        Path resource = Files.createDirectories(directory.resolve("src/main/resources")).resolve("application.yml");
        String yaml = """
                public-plugin-name: PublicOnly
                spring:
                  datasource:
                    password: '${UNAVAILABLE_DATABASE_SECRET}'
                  paper:
                    velocity:
                      enabled: true
                      secret-file: '${UNAVAILABLE_FORWARDING_SECRET_FILE}'
                  velocity:
                    server:
                      forwarding-secret-file: '${UNAVAILABLE_FORWARDING_SECRET_FILE}'
                """;
        Files.writeString(resource, yaml);
        runner("assemble").build();
        try (var jar = new JarFile(directory.resolve("build/libs/usage-1.0.jar").toFile())) {
            String descriptor = new String(jar.getInputStream(jar.getJarEntry(host.equals("paper") ? "plugin.yml" : "velocity-plugin.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(descriptor.contains("PublicOnly"), descriptor);
            assertFalse(descriptor.contains("UNAVAILABLE"), descriptor);
            assertEquals(yaml, new String(jar.getInputStream(jar.getJarEntry("application.yml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertFalse(Files.exists(directory.resolve("run")));
    }

    @ParameterizedTest @ValueSource(strings = {"extension", "property"})
    void selectedLegacyYamlKeepsVersionOnlyCoordinatesWithoutResolvingDevelopmentInputs(String selection) throws Exception {
        fixture("paper", false);
        Files.writeString(directory.resolve("chosen.yml"), """
                spring:
                  paper:
                    minecraft:
                      version: '26.3'
                      api-coordinate: '26.3.build.244-stable'
                      api-version: '26.3'
                    development:
                      jvm-args: ['${UNAVAILABLE_RUNTIME_ARGUMENT}']
                """);
        if (selection.equals("extension")) Files.writeString(directory.resolve("build.gradle"),
                "springPaper.configurationFile = layout.projectDirectory.file('chosen.yml')\n", java.nio.file.StandardOpenOption.APPEND);
        runner(selection.equals("extension") ? new String[]{"assemble"} : new String[]{"assemble", "-PspringPaper.configuration=chosen.yml"}).build();
        assertTrue(Files.exists(directory.resolve("build/libs/usage-1.0.jar")));
        assertFalse(Files.exists(directory.resolve("run")));
    }

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void hostApiIsExcludedEvenWhenAConsumerDependencyExposesItAtRuntime(String host) throws Exception {
        fixture(host, true);
        String coordinate = host.equals("paper") ? "io.papermc.paper:paper-api:26.3.build.244-stable"
                : "com.velocitypowered:velocity-api:4.2.1-SNAPSHOT";
        Files.writeString(directory.resolve("build.gradle"), "dependencies { implementation '" + coordinate + "' }\n", java.nio.file.StandardOpenOption.APPEND);
        runner("assemble").build();
        try (var jar = new JarFile(directory.resolve("build/libs/usage-1.0.jar").toFile())) {
            assertNull(jar.getJarEntry("host/CompileOnly.class"));
        }
    }

    @Test void legacyVelocityAssemblesButNativePreparationRejectsItBeforeReadingUnusedYaml() throws Exception {
        fixture("velocity", true);
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/Application", null, "java/lang/Object", null);
        var plugin = writer.visitAnnotation("Lcom/velocitypowered/api/plugin/Plugin;", true);
        plugin.visit("id", "example"); plugin.visit("name", "Example"); plugin.visitEnd(); writer.visitEnd();
        Files.write(directory.resolve("classes/example/Application.class"), writer.toByteArray());
        Files.writeString(Files.createDirectories(directory.resolve("src/main/resources")).resolve("application.yml"), "broken: [ unused runtime YAML\n");
        Files.writeString(directory.resolve("build.gradle"), "springVelocity.development { version = '4.2.1-SNAPSHOT'; buildNumber = 43 }\n", java.nio.file.StandardOpenOption.APPEND);
        runner("assemble").build();
        for (String task : java.util.List.of("prepareVelocityServer", "runVelocity")) {
            var result = runner(task).buildAndFail();
            assertTrue(result.getOutput().contains("Native Velocity preparation requires @SpringVelocityApplication"), result.getOutput());
            assertFalse(result.getOutput().contains("Pre-start ConfigData loading failed"), result.getOutput());
        }
        assertFalse(Files.exists(directory.resolve("run")));
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "zero"})
    void legacyPaperKeepsTheExistingLatestSelectorWhenBuildIsOmittedOrZero(String build) throws Exception {
        fixture("paper", false);
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/Application", null, "java/lang/Object", null);
        var application = writer.visitAnnotation("Lorg/blu3fishez/springpaper/annotation/SpringPaperPluginApplication;", true);
        application.visit("name", "LegacyPaper"); application.visitEnd(); writer.visitEnd();
        Files.write(directory.resolve("classes/example/Application.class"), writer.toByteArray());
        Files.writeString(Files.createDirectories(directory.resolve("src/main/resources")).resolve("application.yml"), """
                spring.paper.minecraft:
                  version: '26.3'
                  api-coordinate: '26.3.build.244-stable'
                  api-version: '26.3'
                %s
                """.formatted(build.equals("zero") ? "  build: 0" : ""));
        Files.writeString(directory.resolve("build.gradle"), """
                tasks.register('inspectLegacySelector') {
                    inputs.property('selector', tasks.named('runServer').flatMap { it.build }.map { it.class.simpleName }).optional(true)
                    inputs.property('version', tasks.named('runServer').flatMap { it.version })
                    doLast {
                        assert inputs.properties.selector == 'Latest'
                        assert inputs.properties.version == '26.3'
                    }
                }
                """, java.nio.file.StandardOpenOption.APPEND);
        runner("inspectLegacySelector").build();
        assertTrue(runner("inspectLegacySelector").build().getOutput().contains("Reusing configuration cache."));
        assertFalse(Files.exists(directory.resolve("run")));
    }

    private void fixture(String host, boolean target) throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='usage'\n");
        String dsl = !target ? "" : host.equals("paper") ? """
                springPaper.paper {
                    apiCoordinate = 'io.papermc.paper:paper-api:26.3.build.244-stable'
                    apiVersion = '26.3'
                }
                """ : "springVelocity.apiCoordinate = 'com.velocitypowered:velocity-api:4.2.1-SNAPSHOT'\n";
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'org.blu3fishez.spring-%s' }
                version = '1.0'
                repositories { clear(); maven { url = uri('repository') } }
                sourceSets.main.output.classesDirs.from(layout.projectDirectory.dir('classes'))
                %s
                """.formatted(host, dsl));
        var version = new Properties();
        try (var input = SpringPaperPlugin.class.getResourceAsStream("/spring-paper-version.properties")) { version.load(input); }
        artifact("org.blu3fishez.springpaper", "spring-paper-" + (host.equals("paper") ? "core" : "velocity"), version.getProperty("version"), Map.of());
        if (host.equals("paper")) artifact("io.papermc.paper", "paper-api", "26.3.build.244-stable", Map.of("host/CompileOnly.class", type("host/CompileOnly", null)));
        else artifact("com.velocitypowered", "velocity-api", "4.2.1-SNAPSHOT", Map.of("host/CompileOnly.class", type("host/CompileOnly", null)));
        Path classes = Files.createDirectories(directory.resolve("classes/example"));
        Files.write(classes.resolve("Application.class"), type("example/Application", host));
    }
    private GradleRunner runner(String... tasks) {
        var args = new java.util.ArrayList<>(java.util.List.of(tasks));
        args.addAll(java.util.List.of("--offline", "--max-workers=1", "--console=plain", "--configuration-cache"));
        return GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath().withArguments(args);
    }
    private byte[] type(String name, String host) { return type(name, host, "Example"); }
    private byte[] type(String name, String host, String pluginName) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        if (host != null) {
            var annotation = writer.visitAnnotation(host.equals("paper") ? "Lorg/blu3fishez/springpaper/annotation/SpringPaperApplication;"
                    : "Lorg/blu3fishez/springpaper/velocity/annotation/SpringVelocityApplication;", true);
            annotation.visit("name", pluginName);
            if (host.equals("velocity")) annotation.visit("id", "example");
            annotation.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
    private void artifact(String group, String name, String version, Map<String, byte[]> entries) throws Exception {
        Path location = Files.createDirectories(directory.resolve("repository").resolve(group.replace('.', '/')).resolve(name).resolve(version));
        Files.writeString(location.resolve(name + "-" + version + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version></project>");
        try (var output = new JarOutputStream(Files.newOutputStream(location.resolve(name + "-" + version + ".jar")))) {
            for (var entry : entries.entrySet()) { output.putNextEntry(new JarEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry(); }
        }
    }
}
