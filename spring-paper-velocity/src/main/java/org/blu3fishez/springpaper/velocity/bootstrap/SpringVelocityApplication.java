package org.blu3fishez.springpaper.velocity.bootstrap;

import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.blu3fishez.springpaper.velocity.internal.VelocityInputRegistrar;
import org.slf4j.Logger;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;

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
    private final Object lifecycleMonitor = new Object();
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final VelocityInputRegistrar inputs;
    private volatile boolean ready;
    private boolean startupAttempted;
    private AnnotationConfigApplicationContext context;

    @SafeVarargs
    public SpringVelocityApplication(Object plugin, ProxyServer proxy, Logger logger, Path dataDirectory,
            Class<?> configuration, ApplicationContextInitializer<ConfigurableApplicationContext>... initializers) {
        this.plugin = Objects.requireNonNull(plugin);
        this.proxy = Objects.requireNonNull(proxy);
        this.logger = Objects.requireNonNull(logger);
        this.dataDirectory = Objects.requireNonNull(dataDirectory);
        this.configuration = Objects.requireNonNull(configuration);
        this.initializers = List.copyOf(Arrays.asList(initializers));
        this.classLoader = plugin.getClass().getClassLoader();
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
            // AbstractApplicationContext captures TCCL during construction, before setClassLoader.
            // Keep construction, initializers, refresh and failure destruction in this same scope.
            context = new AnnotationConfigApplicationContext();
            try {
                context.setClassLoader(classLoader);
                context.setAllowBeanDefinitionOverriding(false);
                context.setBeanNameGenerator(FullyQualifiedAnnotationBeanNameGenerator.INSTANCE);
                VelocityHostConfiguration.register(context, plugin, proxy, logger, dataDirectory);
                for (var initializer : initializers) initializer.initialize(context);
                context.register(configuration);
                context.addBeanFactoryPostProcessor(VelocityInputRegistrar::validateDefinitions);
                if (stopping.get()) {
                    context.close();
                    return;
                }
                context.refresh();
                inputs.register(context);
                ready = !stopping.get();
                if (stopping.get()) context.close();
            } catch (RuntimeException | Error failure) {
                ready = false;
                try { inputs.close(); } catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
                try { context.close(); } catch (RuntimeException | Error cleanup) { suppress(failure, cleanup); }
                throw failure;
            }
        }
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
