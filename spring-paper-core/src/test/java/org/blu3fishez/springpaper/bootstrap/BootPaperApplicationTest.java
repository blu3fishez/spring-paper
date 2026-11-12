package org.blu3fishez.springpaper.bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.boot.ApplicationRootMetadata;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ConfigurableApplicationContext;

@Timeout(15)
class BootPaperApplicationTest {
    private static final String FIXTURE = "org.blu3fishez.springpaper.fixture.boot";
    private static final String ROOT = FIXTURE + "app.BootRoot";
    private static final String LEGACY = FIXTURE + "legacy.LegacyCarrier";
    private static final String BRIDGE = SpringPaperApplicationPlugin.class.getName();
    private static final String AUTO = FIXTURE + "auto.NativeFeatureAutoConfiguration";
    private static final String USER_MODULE = FIXTURE + "legacy.UserFeatureModule";
    @TempDir Path directory;

    @Test void nativeBridgeUsesOwnRootAndConfigDataTypedPropertiesAndTheManagedProxy() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data"));
        Files.writeString(data.resolve("application.yml"), "game.message: external-base\nspring.paper.fixture.enabled: true\n");
        try (var loader = loader("ordinary.jar", ROOT, ordinaryResources(""))) {
            JavaPlugin host = host(loader.loadClass(BRIDGE).asSubclass(JavaPlugin.class), data);
            var registrations = registerInputs(host);
            doCallRealMethod().when(host).onEnable();
            doCallRealMethod().when(host).onDisable();
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            host.onEnable();
            try {
                Object probe = probe(loader);
                ConfigurableApplicationContext context = context(probe);
                assertSame(previous, Thread.currentThread().getContextClassLoader());
                assertSame(loader.loadClass(ROOT), ApplicationRootMetadata.load(host.getClass(), "paper"));
                assertSame(loader, context.getClassLoader());
                assertSame(loader, context.getBeanFactory().getBeanClassLoader());
                assertTrue(context.getResource("classpath:org/bukkit/command/CommandExecutor.class").exists());
                assertEquals(Map.of("paperPlugin", host), context.getBeansOfType(JavaPlugin.class));
                Object service = context.getBean("greetingService");
                assertSame(host, service.getClass().getMethod("host").invoke(service));
                assertEquals("external-base", service.getClass().getMethod("message").invoke(service));
                Object captured = probe.getClass().getField("captured").get(probe);
                assertSame(Thread.currentThread(), captured.getClass().getMethod("thread").invoke(captured));
                assertEquals("external-base", captured.getClass().getMethod("message").invoke(captured));
                assertTrue(context.containsBean("nativeFlagMarker"), "ConfigData true must reach actual Boot auto-configuration");
                assertFalse(context.containsBean("userFeatureMarker"));
                assertEquals(0, loader.loadClass(USER_MODULE).getField("constructions").get(null),
                        "Ordinary roots use Boot imports without discovering legacy user modules");
                assertEquals(1, context.getBeansOfType(PaperCoreConfiguration.class).size());
                assertEquals(1, registrations.size());
                Object proxy = context.getBean("listener");
                assertTrue(AopUtils.isJdkDynamicProxy(proxy));
                assertSame(loader, proxy.getClass().getClassLoader());
                registrations.getFirst().invoke(loader);
                assertTrue(events(probe).containsAll(List.of("advice", "input:external-base")));
                assertOrdered(events(probe), "runner", "start", "boot-ready", "accepting", "paper-ready");
                assertEquals(ReadinessState.ACCEPTING_TRAFFIC, context.getBean(ApplicationAvailability.class).getReadinessState());
                context.close(); // Public context close must also enter native cleanup before bean destruction.
                assertOrdered(events(probe), "stop", "destroy");
                assertEquals(1, events(probe).stream().filter("stop"::equals).count());
                assertFalse(context.isActive());
                int before = events(probe).size();
                registrations.getFirst().invoke(loader);
                assertEquals(before, events(probe).size(), "Detached callbacks must fence the managed proxy");
            } finally { host.onDisable(); }
        }
    }

    @Test void cleansNativeActivationBeforeSpringDestructionForPartialAndBootReadyFailures() throws Exception {
        for (String failure : List.of("partial", "ready", "runner", "refresh")) {
            Path data = Files.createDirectory(directory.resolve(failure));
            try (var loader = loader(failure + ".jar", ROOT, ordinaryResources(failure))) {
                JavaPlugin host = host(loader.loadClass(BRIDGE).asSubclass(JavaPlugin.class), data);
                var inputs = registerInputs(host);
                var error = assertThrows(IllegalStateException.class,
                        () -> SpringPaperApplication.run(host, loader.loadClass(ROOT)));
                String expected = failure.equals("ready") ? "fixture Boot ready failure" : "fixture " + failure + " failure";
                assertEquals(expected, rootCause(error).getMessage());
                Object probe = probe(loader);
                List<String> events = events(probe);
                assertFalse(context(probe).isActive());
                assertFalse(events.contains("accepting"));
                assertFalse(events.contains("paper-ready"));
                if (Set.of("partial", "ready").contains(failure)) {
                    assertOrdered(events, "start", "stop", "destroy");
                    assertEquals(1, events.stream().filter("stop"::equals).count());
                } else {
                    assertFalse(events.contains("start"));
                    assertFalse(events.contains("stop"));
                    assertTrue(events.contains("destroy"));
                }
                assertEquals(failure.equals("ready") ? 1 : 0, inputs.size());
                int before = events.size();
                for (var input : inputs) input.invoke(loader);
                assertEquals(before, events.size());
            }
        }
    }

    @Test void workerContextCloseRejectsPromptlyAndLeavesNormalMainShutdownAvailable() throws Exception {
        Path data = Files.createDirectory(directory.resolve("rejected-close"));
        try (var loader = loader("rejected-close.jar", ROOT, ordinaryResources(""))) {
            JavaPlugin host = host(loader.loadClass(BRIDGE).asSubclass(JavaPlugin.class), data);
            var inputs = registerInputs(host);
            try (var app = SpringPaperApplication.run(host, loader.loadClass(ROOT))) {
                var context = app.context();
                Object probe = probe(loader);
                PaperTasks tasks = context.getBean(PaperTasks.class);
                CompletableFuture<Void> rejected = CompletableFuture.runAsync(context::close,
                        action -> Thread.ofVirtual().start(action));
                var failure = assertThrows(ExecutionException.class, () -> rejected.get(2, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertEquals("Stop Spring Paper on the server thread", failure.getCause().getMessage());
                assertTrue(context.isActive());
                assertFalse(events(probe).contains("stop"));
                assertFalse(events(probe).contains("destroy"));
                assertEquals(42, tasks.async(() -> 42).get(2, TimeUnit.SECONDS));
                inputs.getFirst().invoke(loader);
                assertTrue(events(probe).contains("input:packaged-profile"));
                app.close();
                assertOrdered(events(probe), "stop", "destroy");
                assertEquals(1, events(probe).stream().filter("stop"::equals).count());
                assertFalse(context.isActive());
                assertThrows(ExecutionException.class, () -> tasks.async(() -> 42).get(2, TimeUnit.SECONDS));
                int before = events(probe).size();
                inputs.getFirst().invoke(loader);
                assertEquals(before, events(probe).size());
            }
        }
    }

    @Test void absentAndFalseOptionalFlagsStayDisabledAfterConfigData() throws Exception {
        for (String flag : List.of("", "spring.paper.fixture.enabled: false\n")) {
            Path data = Files.createTempDirectory(directory, "disabled");
            var resources = ordinaryResources("");
            resources.put("application.yml", "game.message: present\n" + flag);
            try (var loader = loader("disabled-" + data.getFileName() + ".jar", ROOT, resources)) {
                var host = host(loader.loadClass(BRIDGE).asSubclass(JavaPlugin.class), data);
                registerInputs(host);
                try (var app = SpringPaperApplication.run(host, loader.loadClass(ROOT))) {
                    assertFalse(app.context().containsBean("nativeFlagMarker"));
                }
            }
        }
    }

    @Test void legacyCarrierKeepsExplicitResourceProfileOverlayAndMainInitializersInTheBootRuntime() throws Exception {
        Path data = Files.createDirectory(directory.resolve("legacy-data"));
        Files.writeString(data.resolve("legacy.yaml"), "game.message: external-base\n");
        Map<String, String> resources = Map.of("legacy.yaml", "spring.profiles.active: demo\ngame.message: packaged-base\n",
                "legacy-demo.yaml", "game.message: packaged-profile\nspring.paper.fixture.enabled: true\n",
                "application.yml", "game.message: must-not-be-loaded\n");
        try (var loader = loader("legacy.jar", LEGACY, resources)) {
            JavaPlugin host = host(loader.loadClass(LEGACY).asSubclass(JavaPlugin.class), data);
            try (var app = SpringPaperApplication.run(host)) {
                assertEquals("packaged-profile", app.context().getBean("legacyMessage"));
                assertEquals("packaged-profile", app.context().getEnvironment().getProperty("game.message"));
                assertArrayEquals(new String[] { "demo" }, app.context().getEnvironment().getActiveProfiles());
                assertTrue(app.context().containsBean("nativeFlagMarker"));
                assertEquals("legacy-user-spi", app.context().getBean("userFeatureMarker"));
                for (String count : List.of("constructions", "propertyChecks", "configurations")) {
                    assertEquals(1, loader.loadClass(USER_MODULE).getField(count).get(null), "Legacy user SPI " + count);
                }
                Object captured = app.context().getBean("legacyCaptured");
                assertSame(Thread.currentThread(), captured.getClass().getMethod("thread").invoke(captured));
                assertSame(host, captured.getClass().getMethod("host").invoke(captured));
            }
        }
    }

    @Test void twoArtifactsKeepTheirOwnRootAndRejectAParentOwnedRoot() throws Exception {
        try (var first = loader("first.jar", ROOT, ordinaryResources(""));
             var second = loader("second.jar", ROOT, ordinaryResources(""))) {
            Class<?> a = ApplicationRootMetadata.load(first.loadClass(BRIDGE), "paper");
            Class<?> b = ApplicationRootMetadata.load(second.loadClass(BRIDGE), "paper");
            assertNotSame(a, b);
            assertSame(first, a.getClassLoader());
            assertSame(second, b.getClassLoader());
            assertEquals(ApplicationRootMetadata.artifact(first.loadClass(BRIDGE)), ApplicationRootMetadata.artifact(a));
        }
        String unicodeRoot = FIXTURE + "legacy.루트Application";
        try (var unicode = loader("unicode.jar", unicodeRoot, Map.of())) {
            assertEquals(unicodeRoot, ApplicationRootMetadata.load(unicode.loadClass(BRIDGE), "paper").getName());
        }
        try (var wrong = loader("wrong.jar", getClass().getName(), Map.of())) {
            var failure = assertThrows(IllegalArgumentException.class,
                    () -> ApplicationRootMetadata.load(wrong.loadClass(BRIDGE), "paper"));
            assertEquals("Application root must belong to the native plugin artifact", failure.getMessage());
        }
    }

    private Map<String, String> ordinaryResources(String failure) {
        return new LinkedHashMap<>(Map.of("application.yml", "spring.profiles.active: demo\ngame.message: packaged-base\nfixture.failure: "
                + failure + "\n", "application-demo.yml", "game.message: packaged-profile\n"));
    }

    private PluginJarLoader loader(String name, String root, Map<String, String> resources) throws Exception {
        Path artifact = directory.resolve(name);
        Path compiled = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            for (String part : List.of("bootapp", "bootauto", "bootlegacy")) {
                String prefix = "org/blu3fishez/springpaper/fixture/" + part + "/";
                write(jar, prefix, new byte[0]);
                try (var paths = Files.walk(compiled.resolve(prefix))) {
                    for (Path path : paths.filter(Files::isRegularFile).toList()) {
                        write(jar, compiled.relativize(path).toString(), Files.readAllBytes(path));
                    }
                }
            }
            String bridgePath = BRIDGE.replace('.', '/') + ".class";
            try (var input = SpringPaperApplicationPlugin.class.getClassLoader().getResourceAsStream(bridgePath)) {
                write(jar, bridgePath, input.readAllBytes());
            }
            write(jar, ApplicationRootMetadata.LOCATION, ("host=paper\nroot-class=" + root + "\n").getBytes(StandardCharsets.UTF_8));
            write(jar, "META-INF/services/org.blu3fishez.springpaper.spi.SpringPaperModule",
                    (USER_MODULE + "\n").getBytes(StandardCharsets.UTF_8));
            String imports = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
            try (var input = org.springframework.boot.autoconfigure.EnableAutoConfiguration.class.getClassLoader().getResourceAsStream(imports)) {
                write(jar, imports, (new String(input.readAllBytes(), StandardCharsets.UTF_8) + "\n" + AUTO + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
            for (var entry : resources.entrySet()) write(jar, entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return new PluginJarLoader(artifact.toUri().toURL());
    }

    private static void write(JarOutputStream jar, String name, byte[] bytes) throws IOException {
        jar.putNextEntry(new JarEntry(name)); jar.write(bytes); jar.closeEntry();
    }

    private JavaPlugin host(Class<? extends JavaPlugin> type, Path data) {
        JavaPlugin host = mock(type);
        Server server = mock(Server.class);
        Thread main = Thread.currentThread();
        when(server.isPrimaryThread()).thenAnswer(call -> Thread.currentThread() == main);
        when(server.getScheduler()).thenReturn(mock(BukkitScheduler.class));
        when(host.getServer()).thenReturn(server);
        when(host.getName()).thenReturn("Ordinary");
        when(host.namespace()).thenReturn("ordinary");
        when(host.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(host.getDataFolder()).thenReturn(data.toFile());
        when(host.isEnabled()).thenReturn(true);
        when(host.getResource(anyString())).thenAnswer(call -> type.getClassLoader().getResourceAsStream(call.getArgument(0)));
        return host;
    }

    private List<Input> registerInputs(JavaPlugin host) {
        var inputs = new ArrayList<Input>();
        PluginManager manager = mock(PluginManager.class);
        when(host.getServer().getPluginManager()).thenReturn(manager);
        doAnswer(call -> { inputs.add(new Input(call.getArgument(1), call.getArgument(3))); return null; })
                .when(manager).registerEvent(any(), any(Listener.class), any(EventPriority.class), any(EventExecutor.class), eq(host), anyBoolean());
        return inputs;
    }

    private record Input(Listener listener, EventExecutor executor) {
        void invoke(ClassLoader loader) throws Exception {
            Event event = (Event) loader.loadClass(ROOT + "$Signal").getConstructor().newInstance();
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(ClassLoader.getPlatformClassLoader());
            try { executor.execute(listener, event); }
            finally { Thread.currentThread().setContextClassLoader(previous); }
        }
    }
    private Object probe(ClassLoader loader) throws Exception { return loader.loadClass(ROOT).getField("lastProbe").get(null); }
    private ConfigurableApplicationContext context(Object probe) throws Exception {
        return (ConfigurableApplicationContext) probe.getClass().getField("context").get(probe);
    }
    @SuppressWarnings("unchecked") private List<String> events(Object probe) throws Exception {
        return (List<String>) probe.getClass().getField("events").get(probe);
    }
    private Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    private void assertOrdered(List<String> events, String... expected) {
        int previous = -1;
        for (String event : expected) {
            int current = events.indexOf(event);
            assertTrue(current > previous, () -> "Expected ordered " + List.of(expected) + ", observed " + events);
            previous = current;
        }
    }

    private static final class PluginJarLoader extends URLClassLoader {
        PluginJarLoader(URL artifact) { super(new URL[] { artifact }, BootPaperApplicationTest.class.getClassLoader()); }
        @Override public URL getResource(String name) { return findResource(name); }
        @Override public Enumeration<URL> getResources(String name) throws IOException { return findResources(name); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (!name.startsWith(FIXTURE) && !name.equals(BRIDGE)) return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) found = findClass(name);
                if (resolve) resolveClass(found);
                return found;
            }
        }
    }
}
