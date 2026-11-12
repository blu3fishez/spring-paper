package org.blu3fishez.springpaper.velocity.bootstrap;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.blu3fishez.springpaper.boot.BootApplication;
import org.blu3fishez.springpaper.boot.config.ConfigDataSettings;
import org.blu3fishez.springpaper.velocity.internal.VelocityInputRegistrar;
import org.slf4j.Logger;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Connects one Velocity-created plugin to one Spring context. Delegate the native initialize/shutdown
 * events and use {@link #isReady()} for the consumer's admission policy. No server shutdown policy is imposed.
 */
public final class SpringVelocityApplication {
    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Class<?> configuration;
    private final List<ApplicationContextInitializer<ConfigurableApplicationContext>> initializers;
    private final ClassLoader classLoader;
    private final Path artifact;
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final VelocityInputRegistrar inputs;
    private volatile boolean ready;
    private boolean startupAttempted;
    private AnnotationConfigApplicationContext context;

    @SafeVarargs
    public SpringVelocityApplication(Object plugin, ProxyServer proxy, Logger logger, Path dataDirectory,
            Class<?> configuration, ApplicationContextInitializer<ConfigurableApplicationContext>... initializers) {
        this(plugin, proxy, logger, dataDirectory, configuration, null, initializers);
    }

    @SafeVarargs
    SpringVelocityApplication(Object plugin, ProxyServer proxy, Logger logger, Path dataDirectory,
            Class<?> configuration, Path artifact,
            ApplicationContextInitializer<ConfigurableApplicationContext>... initializers) {
        this.plugin = Objects.requireNonNull(plugin);
        this.proxy = Objects.requireNonNull(proxy);
        this.logger = Objects.requireNonNull(logger);
        this.dataDirectory = Objects.requireNonNull(dataDirectory);
        this.configuration = Objects.requireNonNull(configuration);
        this.initializers = List.copyOf(Arrays.asList(initializers));
        this.classLoader = plugin.getClass().getClassLoader();
        this.artifact = artifact;
        this.inputs = new VelocityInputRegistrar(plugin, proxy, stopping::get);
    }

    /** Native asynchronous initialization, including complete Spring lifecycle startup before inputs. */
    public EventTask initialize() { return EventTask.async(() -> withLoader(this::startContext)); }

    /**
     * Fences readiness and input acquisition immediately. The returned native async task waits for any
     * in-progress initialization, then closes Spring resources. Always return it from the host subscriber.
     */
    public EventTask shutdown() {
        stopping.set(true);
        ready = false;
        Throwable failure = null;
        try { withLoader(inputs::close); }
        catch (RuntimeException | Error error) { failure = error; }
        Throwable inputFailure = failure;
        return EventTask.async(() -> withLoader(() -> closeContext(inputFailure)));
    }

    /** True only after refresh and all native registrations succeed, and never after shutdown is requested. */
    public boolean isReady() { return ready && !stopping.get(); }

    private void startContext() {
        synchronized (lifecycleMonitor) {
            if (stopping.get()) return;
            if (startupAttempted) {
                if (ready) return;
                throw new IllegalStateException("Velocity application initialization already failed");
            }
            startupAttempted = true;
            try {
                var environment = artifact == null ? new StandardEnvironment()
                        : ConfigDataSettings.prepare(artifact, dataDirectory);
                var application = new BootApplication(configuration, classLoader,
                        new DefaultResourceLoader(classLoader), environment, artifact != null,
                        new BootApplication.Hooks(this::initializeContext, ignored -> {}, this::activate, this::beforeClose));
                application.setBeanNameGenerator(FullyQualifiedAnnotationBeanNameGenerator.INSTANCE);
                application.run();
            } catch (RuntimeException | Error failure) {
                ready = false;
                try { inputs.close(); } catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
                try { if (context != null) context.close(); }
                catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
                throw failure;
            }
        }
    }

    private void initializeContext(AnnotationConfigApplicationContext created) {
        context = created;
        VelocityHostConfiguration.register(created, plugin, proxy, logger, dataDirectory);
        for (var initializer : initializers) initializer.initialize(created);
        created.addBeanFactoryPostProcessor(VelocityInputRegistrar::validateDefinitions);
        if (stopping.get()) throw new IllegalStateException("Velocity application is stopping");
    }

    private void activate(AnnotationConfigApplicationContext started) {
        inputs.register(started);
        ready = !stopping.get();
        if (stopping.get()) throw new IllegalStateException("Velocity application is stopping");
    }

    private void beforeClose() {
        ready = false;
        inputs.close();
    }

    private void closeContext(Throwable failure) {
        synchronized (lifecycleMonitor) {
            try { inputs.close(); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else suppress(failure, cleanup);
            }
            try { if (context != null) context.close(); }
            catch (RuntimeException | Error cleanup) {
                if (failure == null) failure = cleanup;
                else suppress(failure, cleanup);
            }
        }
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    private void withLoader(Runnable action) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(classLoader);
        try { action.run(); }
        finally { thread.setContextClassLoader(previous); }
    }

    private static void suppress(Throwable failure, Throwable cleanup) {
        if (failure != cleanup) failure.addSuppressed(cleanup);
    }
}
