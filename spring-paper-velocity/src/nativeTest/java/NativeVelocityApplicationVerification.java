import com.google.inject.AbstractModule;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.util.ProxyVersion;
import com.velocitypowered.proxy.plugin.PluginClassLoader;
import com.velocitypowered.proxy.plugin.loader.VelocityPluginContainer;
import com.velocitypowered.proxy.plugin.loader.java.JavaPluginLoader;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.slf4j.Logger;

/** Controlled native loader/Guice integration. Compile against the fixed native JAR; starts no proxy. */
public final class NativeVelocityApplicationVerification {
    private static final String BRIDGE = "org.blu3fishez.springpaper.velocity.bootstrap.SpringVelocityApplicationPlugin";
    private static final String METADATA = "META-INF/spring-paper/application.properties";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected final plugin JAR and empty scratch directory");
        Path artifact = Path.of(args[0]).toAbsolutePath();
        Path scratch = Path.of(args[1]).toAbsolutePath();
        Files.createDirectory(scratch);
        List<PluginClassLoader> classLoaders = new ArrayList<>();
        List<Object> plugins = new ArrayList<>();
        List<CapturedHost> hosts = new ArrayList<>();
        String rootName;
        try (var jar = new JarFile(artifact.toFile())) {
            var metadata = new Properties();
            try (var input = jar.getInputStream(jar.getJarEntry(METADATA))) { metadata.load(input); }
            require("velocity".equals(metadata.getProperty("host")), "generated host metadata");
            rootName = metadata.getProperty("root-class");
            require(rootName != null && !rootName.isBlank(), "generated ordinary root metadata");
            for (String prefix : List.of("com/velocitypowered/", "com/google/inject/", "org/gradle/",
                    "org/springframework/web/", "jakarta/servlet/", "org/apache/catalina/")) {
                require(jar.stream().noneMatch(entry -> entry.getName().startsWith(prefix)), "forbidden bundled host/tool/web classes: " + prefix);
            }
            require(jar.getJarEntry("org/springframework/boot/SpringApplication.class") != null, "real Boot runtime bundled");
        }
        var loader = new JavaPluginLoader(nativeProxy(new CapturedHost("loader", scratch)), scratch.resolve("plugins"));
        try {
            for (String label : List.of("first", "second")) {
                Path ownJar = scratch.resolve(label + ".jar");
                Files.copy(artifact, ownJar, StandardCopyOption.COPY_ATTRIBUTES);
                Path data = Files.createDirectories(scratch.resolve("plugins").resolve(label));
                Files.writeString(data.resolve("application.properties"),
                        "spring.profiles.active=fixture\ngateway.greeting=external-base-" + label + "\n");
                Files.writeString(data.resolve("application-fixture.properties"), "gateway.greeting=" + label + "-native-fixture\n");
                CapturedHost host = new CapturedHost(label, data);
                ProxyServer proxy = nativeProxy(host);
                Logger logger = nativeLogger(host);
                var candidate = loader.loadCandidate(ownJar);
                var description = loader.createPluginFromCandidate(candidate);
                var container = new VelocityPluginContainer(description);
                loader.createPlugin(container, new AbstractModule() {
                    @Override protected void configure() {
                        bind(ProxyServer.class).toInstance(proxy);
                        bind(Logger.class).toInstance(logger);
                        bind(Path.class).annotatedWith(DataDirectory.class).toInstance(data);
                    }
                });
                Object plugin = container.getInstance().orElseThrow();
                plugins.add(plugin);
                hosts.add(host);
                Class<?> bridge = plugin.getClass();
                require(BRIDGE.equals(bridge.getName()), "generated descriptor selects common native bridge");
                require(bridge.getAnnotation(Plugin.class) == null, "descriptor is the native entry-point source of truth");
                require(bridge.getClassLoader() instanceof PluginClassLoader, "real native PluginClassLoader");
                PluginClassLoader ownLoader = (PluginClassLoader) bridge.getClassLoader();
                classLoaders.add(ownLoader);
                require(bridge.getProtectionDomain().getCodeSource().getLocation().equals(ownJar.toUri().toURL()), "bridge belongs to own final JAR");
                Class<?> root = Class.forName(rootName, false, ownLoader);
                require(root.getClassLoader() == ownLoader, "ordinary root belongs to own native loader");
                require(root.getProtectionDomain().getCodeSource().getLocation().equals(ownJar.toUri().toURL()), "ordinary root belongs to own final JAR");
                require(!ready(plugin), "readiness starts false");
                execute((EventTask) bridge.getMethod("initialize", ProxyInitializeEvent.class).invoke(plugin, new ProxyInitializeEvent()));
                require(ready(plugin), "public readiness after complete Boot startup");
                require(host.versionCalls.get() == 1, "scanned service uses the exact injected native ProxyServer");
                require(host.messages.stream().anyMatch(message -> message.contains(label + "-native-fixture")
                        && message.contains(label + "-native-version") && message.contains(data.toString())),
                        "Boot ready service sees own typed external profile, native logger, proxy and data directory");
                System.out.println("PASS " + label + ": final JAR -> native JSON loader -> PluginClassLoader -> Guice -> Boot root/service/typed ConfigData -> ready");
            }
            require(plugins.getFirst().getClass() != plugins.getLast().getClass(), "two common bridge FQCNs retain independent class identity");
            require(classLoaders.getFirst() != classLoaders.getLast(), "two final artifacts retain independent native loaders");
            for (int index = plugins.size() - 1; index >= 0; index--) {
                Object plugin = plugins.get(index);
                EventTask shutdown = (EventTask) plugin.getClass().getMethod("shutdown", ProxyShutdownEvent.class)
                        .invoke(plugin, new ProxyShutdownEvent());
                require(!ready(plugin), "shutdown fences readiness before async close");
                execute(shutdown);
                require(!ready(plugin), "readiness stays false after close");
                execute((EventTask) plugin.getClass().getMethod("shutdown", ProxyShutdownEvent.class)
                        .invoke(plugin, new ProxyShutdownEvent()));
                require(hosts.get(index).closes.get() == 0, "Spring never closes native singleton objects");
            }
            System.out.println("PASS isolation/close: both final native loaders coexist, own metadata/profiles stay distinct, shutdown preserves native ownership");
            System.out.println("LIMITS: controlled binding module and direct public EventTask invocation; no native plugin-manager dispatch, proxy process, forwarding or client connection");
        } finally {
            for (Object plugin : plugins) {
                try {
                    execute((EventTask) plugin.getClass().getMethod("shutdown", ProxyShutdownEvent.class)
                            .invoke(plugin, new ProxyShutdownEvent()));
                } catch (Exception failure) { failure.printStackTrace(); }
            }
            for (PluginClassLoader classLoader : classLoaders) classLoader.close();
        }
    }

    private static boolean ready(Object plugin) throws Exception {
        return (boolean) plugin.getClass().getMethod("isReady").invoke(plugin);
    }

    private static void execute(EventTask task) {
        require(task.requiresAsync(), "native lifecycle work requests native async execution");
        var resumed = new AtomicInteger();
        task.execute(new Continuation() {
            @Override public void resume() { resumed.incrementAndGet(); }
            @Override public void resumeWithException(Throwable failure) { throw new AssertionError(failure); }
        });
        require(resumed.get() == 1, "native async continuation resumes once");
    }

    private static ProxyServer nativeProxy(CapturedHost host) {
        return (ProxyServer) Proxy.newProxyInstance(ProxyServer.class.getClassLoader(),
                new Class<?>[] {ProxyServer.class, AutoCloseable.class}, (object, method, args) -> {
                    if (method.getName().equals("getVersion")) {
                        host.versionCalls.incrementAndGet();
                        return new ProxyVersion("Controlled fixture", "Spring Paper", host.label + "-native-version");
                    }
                    if (method.getName().equals("close")) { host.closes.incrementAndGet(); return null; }
                    return objectMethod(object, method.getName(), args);
                });
    }

    private static Logger nativeLogger(CapturedHost host) {
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(),
                new Class<?>[] {Logger.class, AutoCloseable.class}, (object, method, args) -> {
                    if (method.getName().equals("info")) {
                        StringBuilder message = new StringBuilder(String.valueOf(args[0]));
                        for (int index = 1; index < args.length; index++) {
                            if (args[index] instanceof Object[] values) {
                                for (Object value : values) message.append(' ').append(value);
                            } else message.append(' ').append(args[index]);
                        }
                        host.messages.add(message.toString());
                        return null;
                    }
                    if (method.getName().equals("close")) { host.closes.incrementAndGet(); return null; }
                    if (method.getName().equals("getName")) return host.label + "-native-logger";
                    if (method.getReturnType() == boolean.class) return false;
                    return objectMethod(object, method.getName(), args);
                });
    }

    private static Object objectMethod(Object object, String method, Object[] args) {
        return switch (method) {
            case "toString" -> "controlled-native-singleton";
            case "hashCode" -> System.identityHashCode(object);
            case "equals" -> object == args[0];
            default -> throw new AssertionError("Unapproved native operation in controlled fixture: " + method);
        };
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class CapturedHost {
        final String label;
        final Path data;
        final AtomicInteger versionCalls = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final List<String> messages = new ArrayList<>();
        CapturedHost(String label, Path data) { this.label = label; this.data = data; }
    }
}
