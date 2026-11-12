package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** Executes a tiny config-file fixture, never Paper or Velocity or a network listener. */
class NativeLaunchLifecycleTest {
    @TempDir Path directory;

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void successfulFixtureRunsRebaselineDefaultsAndReuseConfigurationCache(String host) throws Exception {
        fixture(host);
        String task = host.equals("paper") ? "runServer" : "runVelocity";
        var first = runner(task).build();
        assertTrue(first.getOutput().contains("BUILD SUCCESSFUL"));
        Path run = directory.resolve("run");
        Path nativeFile = run.resolve(host.equals("paper") ? "server.properties" : "velocity.toml");
        assertTrue(Files.readString(nativeFile).contains("fixture-default"));
        assertEquals("provider-value", Files.readString(run.resolve("spring-property.txt")));
        Properties manifest = new Properties();
        try (var input = Files.newBufferedReader(run.resolve(".spring-paper/owned.properties"))) { manifest.load(input); }
        assertEquals(OwnedRunDirectory.hash(Files.readAllBytes(nativeFile)), manifest.getProperty(nativeFile.getFileName().toString()));
        var second = runner(task).build();
        assertTrue(second.getOutput().contains("Reusing configuration cache."), second.getOutput());
        assertEquals("xx", Files.readString(run.resolve("invocations.txt")));
    }

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void failingFixtureDoesNotRebaselineAndTheNextRunStopsBeforeExecuting(String host) throws Exception {
        fixture(host);
        String task = host.equals("paper") ? "runServer" : "runVelocity";
        var failure = runner(task, "-PfixtureMode=fail").buildAndFail();
        assertTrue(failure.getOutput().contains("non-zero exit value 7"), failure.getOutput());
        Path run = directory.resolve("run");
        String manifest = Files.readString(run.resolve(".spring-paper/owned.properties"));
        var blocked = runner(task, "-PfixtureMode=fail").buildAndFail();
        assertTrue(blocked.getOutput().contains("Managed native configuration drift detected"), blocked.getOutput());
        assertEquals("x", Files.readString(run.resolve("invocations.txt")));
        assertEquals(manifest, Files.readString(run.resolve(".spring-paper/owned.properties")));
    }

    @ParameterizedTest @ValueSource(strings = {"paper", "velocity"})
    void explicitRelativeLocationsUseTheSameWorkingDirectoryAsTheFixtureProcess(String host) throws Exception {
        fixture(host);
        String task = host.equals("paper") ? "runServer" : "runVelocity";
        runner(task).build();
        Files.writeString(directory.resolve("run/relative.yml"), "fixture-location-marker\n");
        runner(task, "-PfixtureLocation=file:./relative.yml").build();
        var repeated = runner(task, "-PfixtureLocation=file:./relative.yml").build();
        assertTrue(repeated.getOutput().contains("Reusing configuration cache."), repeated.getOutput());
        assertEquals("xxx", Files.readString(directory.resolve("run/invocations.txt")));
    }

    @org.junit.jupiter.api.Test void collidingPrepareTargetsStopBeforeTheHelperOrAnyNativeOrReviewOutput() throws Exception {
        fixture("paper");
        Files.writeString(directory.resolve("build.gradle"), """
                tasks.named('preparePaperServer') {
                    nativeArguments = ['--config=eula.txt']
                    launchSystemProperties.put('fixture.helper-marker', layout.projectDirectory.file('helper-started').asFile.absolutePath)
                }
                """, java.nio.file.StandardOpenOption.APPEND);
        var result = runner("preparePaperServer").buildAndFail();
        assertTrue(result.getOutput().contains("Native target path collision"), result.getOutput());
        assertFalse(Files.exists(directory.resolve("helper-started")));
        assertFalse(Files.exists(directory.resolve("run")));
        assertFalse(Files.exists(directory.resolve("build/spring-paper/native-plan.txt")));
        assertFalse(Files.exists(directory.resolve("build/spring-paper/native-proposal.json")));
        // Gradle creates declared output parents before invoking the task action.
        try (var review = Files.list(directory.resolve("build/spring-paper"))) { assertEquals(0, review.count()); }
    }

    @org.junit.jupiter.api.Test void legacyPaperRetainsExistingFilesAndSkipsConfigDataWithTheLocalProcessFixture() throws Exception {
        fixture("paper", true);
        Path run = Files.createDirectory(directory.resolve("run"));
        Files.writeString(run.resolve("server.properties"), "user-owned=true\nrcon.password=retained-fixture-value\n");
        runner("runServer").build();
        assertFalse(Files.exists(directory.resolve("helper-started")));
        String properties = Files.readString(run.resolve("server.properties"));
        assertTrue(properties.contains("user-owned=true"));
        assertTrue(properties.contains("rcon.password=retained-fixture-value"));
        assertTrue(properties.contains("server-port=25565"));
        assertTrue(Files.readString(run.resolve("eula.txt")).contains("eula=false"));
        assertFalse(Files.exists(run.resolve(".spring-paper")));
        assertTrue(runner("runServer").build().getOutput().contains("Reusing configuration cache."));
        assertEquals("xx", Files.readString(run.resolve("invocations.txt")));
    }

    private void fixture(String host) throws Exception { fixture(host, false); }
    private void fixture(String host, boolean legacy) throws Exception {
        Files.writeString(directory.resolve("settings.gradle"), "rootProject.name='launch-fixture'\n");
        String extension = host.equals("paper") ? """
                springPaper {
                    paper { apiCoordinate = 'io.papermc.paper:paper-api:26.3.build.244-stable'; apiVersion = '26.3' }
                    development { minecraftVersion = '26.3'; %s jvmArgs = ['-Dspring.fixture=provider-value'] }
                }
                """.formatted(legacy ? "" : "build = 244;") : """
                springVelocity {
                    apiCoordinate = 'com.velocitypowered:velocity-api:4.2.1-SNAPSHOT'
                    development { version = '4.2.1-SNAPSHOT'; buildNumber = 43; jvmArgs = ['-Dspring.fixture=provider-value'] }
                }
                """;
        Files.writeString(directory.resolve("build.gradle"), """
                plugins { id 'org.blu3fishez.spring-%s' }
                version = '1.0'
                repositories { clear(); maven { url = uri('repository') } }
                sourceSets.main.output.classesDirs.from(layout.projectDirectory.dir('classes'))
                %s
                tasks.named('%s') {
                    runClasspath.from(files('native-fixture.jar'))
                    mainClass = 'fixture.NativeProcess'
                    systemProperty 'spring.fixture', 'task-value'
                    systemProperty 'spring.config.location', providers.gradleProperty('fixtureLocation').orElse('').get()
                    systemProperty 'fixture.mode', providers.gradleProperty('fixtureMode').orElse('normal').get()
                    systemProperty 'fixture.helper-marker', layout.projectDirectory.file('helper-started').asFile.absolutePath
                    environment 'VELOCITY_FORWARDING_SECRET', 'fixture-only-value'
                    args '%s'
                }
                """.formatted(host, extension, host.equals("paper") ? "runServer" : "runVelocity", host));
        Path source = Files.createDirectories(directory.resolve("fixture-src"));
        Files.writeString(source.resolve("ConfigDataProbe.java"), """
                package org.blu3fishez.springpaper.boot.config;
                public class ConfigDataProbe {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Path output = null;
                        for (int i = 0; i < args.length; i++) {
                            if (args[i].equals("--output")) output = java.nio.file.Path.of(args[++i]);
                        }
                        var marker = System.getProperty("fixture.helper-marker");
                        if (marker != null) java.nio.file.Files.writeString(java.nio.file.Path.of(marker), "helper-started");
                        if (!"provider-value".equals(System.getProperty("spring.fixture"))) System.exit(12);
                        var location = System.getProperty("spring.config.location", "");
                        if (!location.isBlank() && !java.nio.file.Files.readString(java.nio.file.Path.of(location.substring(5))).contains("fixture-location-marker")) System.exit(13);
                        java.nio.file.Files.writeString(output, "paper-velocity:\\n  enabled: false\\nvelocity-server: {}\\n");
                    }
                }
                """);
        Files.writeString(source.resolve("NativeProcess.java"), """
                package fixture;
                public class NativeProcess {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Path root = java.nio.file.Path.of("");
                        java.nio.file.Files.writeString(root.resolve("invocations.txt"), "x", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        java.nio.file.Files.writeString(root.resolve("spring-property.txt"), System.getProperty("spring.fixture"));
                        var location = System.getProperty("spring.config.location", "");
                        if (!location.isBlank() && !java.nio.file.Files.readString(java.nio.file.Path.of(location.substring(5))).contains("fixture-location-marker")) System.exit(13);
                        var file = root.resolve(args[0].equals("paper") ? "server.properties" : "velocity.toml");
                        java.nio.file.Files.writeString(file, "\\n# native fixture default\\nfixture-default=true\\n", java.nio.file.StandardOpenOption.APPEND);
                        if (System.getProperty("fixture.mode").equals("fail")) System.exit(7);
                    }
                }
                """);
        Path compiled = Files.createDirectories(directory.resolve("fixture-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", compiled.toString(),
                source.resolve("ConfigDataProbe.java").toString(), source.resolve("NativeProcess.java").toString()));
        byte[] probe = Files.readAllBytes(compiled.resolve("org/blu3fishez/springpaper/boot/config/ConfigDataProbe.class"));
        jar(directory.resolve("native-fixture.jar"), Map.of("fixture/NativeProcess.class", Files.readAllBytes(compiled.resolve("fixture/NativeProcess.class"))));
        var version = new Properties();
        try (var input = SpringPaperPlugin.class.getResourceAsStream("/spring-paper-version.properties")) { version.load(input); }
        artifact("org.blu3fishez.springpaper", "spring-paper-" + (host.equals("paper") ? "core" : "velocity"), version.getProperty("version"),
                Map.of("org/blu3fishez/springpaper/boot/config/ConfigDataProbe.class", probe));
        if (host.equals("paper")) artifact("io.papermc.paper", "paper-api", "26.3.build.244-stable", Map.of());
        else artifact("com.velocitypowered", "velocity-api", "4.2.1-SNAPSHOT", Map.of());
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "example/Application", null, "java/lang/Object", null);
        var annotation = writer.visitAnnotation(legacy ? "Lorg/blu3fishez/springpaper/annotation/SpringPaperPluginApplication;"
                : host.equals("paper") ? "Lorg/blu3fishez/springpaper/annotation/SpringPaperApplication;"
                : "Lorg/blu3fishez/springpaper/velocity/annotation/SpringVelocityApplication;", true);
        annotation.visit("name", "Example");
        if (host.equals("velocity")) annotation.visit("id", "example");
        annotation.visitEnd();
        writer.visitEnd();
        Files.createDirectories(directory.resolve("classes/example"));
        Files.write(directory.resolve("classes/example/Application.class"), writer.toByteArray());
        if (legacy) {
            Files.writeString(Files.createDirectories(directory.resolve("src/main/resources")).resolve("application.yml"),
                    "spring.config.import: unused-legacy-import.yml\nunrelated.password: '${UNAVAILABLE_LEGACY_PLACEHOLDER}'\n");
            Files.writeString(directory.resolve("build.gradle"), "springPaper.development.serverProperties = ['server-port':'25565']\n", java.nio.file.StandardOpenOption.APPEND);
        }
    }
    private GradleRunner runner(String... arguments) {
        var args = new java.util.ArrayList<>(List.of(arguments));
        args.addAll(List.of("--offline", "--max-workers=1", "--console=plain", "--configuration-cache"));
        return GradleRunner.create().withProjectDir(directory.toFile()).withPluginClasspath().withArguments(args);
    }
    private void artifact(String group, String name, String version, Map<String, byte[]> entries) throws Exception {
        Path location = Files.createDirectories(directory.resolve("repository").resolve(group.replace('.', '/')).resolve(name).resolve(version));
        Files.writeString(location.resolve(name + "-" + version + ".pom"), "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version></project>");
        jar(location.resolve(name + "-" + version + ".jar"), entries);
    }
    private void jar(Path file, Map<String, byte[]> entries) throws Exception {
        try (var output = new JarOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) { output.putNextEntry(new JarEntry(entry.getKey())); output.write(entry.getValue()); output.closeEntry(); }
        }
    }
}
