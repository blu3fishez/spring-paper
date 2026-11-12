package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One controlled Java child, with cleanup even when the production implementation is broken. */
class ForkedConfigDataTest {
    @TempDir Path directory;

    @Test void interruptionTerminatesTheChildBeforeReturningAndPreservesTheCallerInterrupt() throws Exception {
        Path artifact = fixture();
        String marker = UUID.randomUUID().toString();
        Path ready = directory.resolve("ready");
        var failure = new AtomicReference<Throwable>();
        var interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            try { read(artifact, ready, marker, "wait"); }
            catch (Throwable error) { failure.set(error); }
            finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        }, "forked-config-data-caller");
        try {
            caller.start();
            Ready child = awaitReady(ready);
            assertTrue(ProcessHandle.of(child.pid()).orElseThrow().isAlive());
            caller.interrupt();
            caller.join(10_000);
            assertFalse(caller.isAlive(), "Interrupted caller did not finish within the cleanup bound");
            assertInstanceOf(GradleException.class, failure.get());
            assertTrue(failure.get().getMessage().contains("Interrupted while preparing ConfigData"));
            assertInstanceOf(InterruptedException.class, failure.get().getCause());
            assertTrue(interrupted.get(), "Caller interrupt flag was lost during child cleanup");
            assertFalse(ProcessHandle.of(child.pid()).map(ProcessHandle::isAlive).orElse(false), "Helper PID survived interruption");
            assertFalse(Files.exists(child.output()), "Private helper output remained after termination");
        } finally { cleanup(caller, marker); }
    }

    @Test void timeoutTerminatesTheChildAndDeletesPrivateOutputBeforeReturning() throws Exception {
        Path artifact = fixture();
        String marker = UUID.randomUUID().toString();
        Path ready = directory.resolve("timeout-ready");
        try {
            var error = assertThrows(GradleException.class, () -> ForkedConfigData.read(
                    Path.of(System.getProperty("java.home"), "bin", "java"), artifact,
                    directory.resolve("run/plugins/fixture"), directory.resolve("run"), System.getenv(),
                    Map.of("fixture.ready", ready.toString(), "fixture.marker", marker, "fixture.mode", "wait"), List.of(), Duration.ofSeconds(2)));
            assertTrue(error.getMessage().contains("exceeded 2 seconds"));
            Ready child = awaitReady(ready);
            assertFalse(ProcessHandle.of(child.pid()).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(Files.exists(child.output()));
        } finally { cleanup(null, marker); }
    }

    @ParameterizedTest @ValueSource(strings = {"success", "failure", "missing", "malformed"})
    void normalResultsAndOutputFailuresLeaveNoLiveChildOrPrivateOutput(String mode) throws Exception {
        Path artifact = fixture();
        String marker = UUID.randomUUID().toString();
        Path ready = directory.resolve("output-ready");
        try {
            if (mode.equals("success")) assertEquals(Map.of("paper-velocity", Map.of("enabled", false), "velocity-server", Map.of()), read(artifact, ready, marker, mode));
            else {
                Class<? extends Throwable> expected = mode.equals("missing") ? java.io.IOException.class : RuntimeException.class;
                assertThrows(expected, () -> read(artifact, ready, marker, mode));
            }
            Ready child = awaitReady(ready);
            assertFalse(ProcessHandle.of(child.pid()).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(Files.exists(child.output()));
            assertFalse(Thread.currentThread().isInterrupted());
        } finally { cleanup(null, marker); }
    }

    private Map<String, Object> read(Path artifact, Path ready, String marker, String mode) throws Exception {
        return ForkedConfigData.read(Path.of(System.getProperty("java.home"), "bin", "java"), artifact,
                directory.resolve("run/plugins/fixture"), directory.resolve("run"), System.getenv(),
                Map.of("fixture.ready", ready.toString(), "fixture.marker", marker, "fixture.mode", mode), List.of());
    }
    private record Ready(long pid, Path output) {}
    private Ready awaitReady(Path ready) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ready)) {
                List<String> lines = Files.readAllLines(ready);
                if (lines.size() == 2 && !lines.get(1).isBlank()) return new Ready(Long.parseLong(lines.get(0)), Path.of(lines.get(1)));
            }
            TimeUnit.MILLISECONDS.sleep(10);
        }
        throw new AssertionError("Controlled helper did not publish its PID and output path");
    }
    private void cleanup(Thread caller, String marker) throws Exception {
        if (caller != null) { caller.interrupt(); caller.join(5_000); }
        List<ProcessHandle> children;
        try (var descendants = ProcessHandle.current().descendants()) {
            children = descendants.filter(process -> Arrays.stream(process.info().arguments().orElseGet(() -> new String[0]))
                    .anyMatch(argument -> argument.equals("-Dfixture.marker=" + marker))).toList();
        }
        for (ProcessHandle child : children) child.destroyForcibly();
        for (ProcessHandle child : children) child.onExit().get(5, TimeUnit.SECONDS);
        if (caller != null) { caller.join(5_000); assertFalse(caller.isAlive(), "Fixture caller survived final cleanup"); }
    }
    private Path fixture() throws Exception {
        Path source = directory.resolve("ConfigDataProbe.java");
        Files.writeString(source, """
                package org.blu3fishez.springpaper.boot.config;
                public class ConfigDataProbe {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Path output = null;
                        for (int i = 0; i < args.length; i++) if (args[i].equals("--output")) output = java.nio.file.Path.of(args[++i]);
                        java.nio.file.Files.writeString(java.nio.file.Path.of(System.getProperty("fixture.ready")),
                            ProcessHandle.current().pid() + "\\n" + output + "\\n");
                        switch (System.getProperty("fixture.mode")) {
                            case "wait" -> Thread.sleep(60_000);
                            case "failure" -> System.exit(7);
                            case "missing" -> { java.nio.file.Files.delete(output); return; }
                            case "malformed" -> { java.nio.file.Files.writeString(output, "broken: [\\n"); return; }
                            default -> { }
                        }
                        java.nio.file.Files.writeString(output, "paper-velocity:\\n  enabled: false\\nvelocity-server: {}\\n");
                    }
                }
                """);
        Path classes = Files.createDirectories(directory.resolve("fixture-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), source.toString()));
        Path artifact = directory.resolve("fixture.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(artifact))) {
            output.putNextEntry(new JarEntry("org/blu3fishez/springpaper/boot/config/ConfigDataProbe.class"));
            output.write(Files.readAllBytes(classes.resolve("org/blu3fishez/springpaper/boot/config/ConfigDataProbe.class")));
            output.closeEntry();
        }
        return artifact;
    }
}
