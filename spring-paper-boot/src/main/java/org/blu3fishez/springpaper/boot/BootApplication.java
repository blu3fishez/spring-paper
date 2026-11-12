package org.blu3fishez.springpaper.boot;

import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ResourceLoader;

/** Internal embedding seam shared by the two native host adapters. It never owns a process. */
public final class BootApplication extends SpringApplication {
    private final ClassLoader nativeClassLoader;
    private final Hooks hooks;
    private BeanNameGenerator beanNameGenerator;

    /** Host work belongs to these callbacks; ordinary bean creation and Boot events remain on the caller. */
    public record Hooks(Consumer<AnnotationConfigApplicationContext> initialize,
            Consumer<AnnotationConfigApplicationContext> prepared,
            Consumer<AnnotationConfigApplicationContext> activate, Runnable beforeClose, Runnable checkCloseAllowed) {
        public Hooks(Consumer<AnnotationConfigApplicationContext> initialize,
                Consumer<AnnotationConfigApplicationContext> prepared,
                Consumer<AnnotationConfigApplicationContext> activate, Runnable beforeClose) {
            this(initialize, prepared, activate, beforeClose, () -> {});
        }
        public Hooks {
            Objects.requireNonNull(initialize);
            Objects.requireNonNull(prepared);
            Objects.requireNonNull(activate);
            Objects.requireNonNull(beforeClose);
            Objects.requireNonNull(checkCloseAllowed);
        }
    }

    public BootApplication(Class<?> source, ClassLoader classLoader, ResourceLoader resources,
            ConfigurableEnvironment environment, boolean configData, Hooks hooks) {
        super(resources, source);
        this.nativeClassLoader = Objects.requireNonNull(classLoader);
        this.hooks = Objects.requireNonNull(hooks);
        setEnvironment(environment);
        setMainApplicationClass(source);
        setWebApplicationType(WebApplicationType.NONE);
        setRegisterShutdownHook(false);
        setKeepAlive(false);
        setAllowBeanDefinitionOverriding(false);
        setBannerMode(Banner.Mode.OFF);
        setLogStartupInfo(false);
        // A plugin must not replace the host's JUL handlers or logging configuration.
        setListeners(getListeners().stream().filter(listener -> !(listener instanceof LoggingApplicationListener)
                && (configData || !(listener instanceof EnvironmentPostProcessorApplicationListener))).toList());
        setApplicationContextFactory(type -> {
            if (type != WebApplicationType.NONE) throw new IllegalStateException("Native applications are non-web");
            return new NativeContext(hooks.beforeClose(), hooks.checkCloseAllowed());
        });
    }

    @Override protected void bindToSpringApplication(ConfigurableEnvironment environment) {
        super.bindToSpringApplication(environment);
        // spring.main binding must not make a native plugin own a web server or JVM shutdown hook.
        setWebApplicationType(WebApplicationType.NONE);
        setRegisterShutdownHook(false);
        setKeepAlive(false);
        setAllowBeanDefinitionOverriding(false);
    }

    @Override protected void postProcessApplicationContext(ConfigurableApplicationContext context) {
        // Boot configures its source loader separately from this context's register/scan readers.
        // The context setter also registers Boot's shared generator singleton, so it owns that one
        // registration; leave every other part of Boot's context preparation unchanged.
        BeanNameGenerator generator = beanNameGenerator;
        if (generator != null) super.setBeanNameGenerator(null);
        try { super.postProcessApplicationContext(context); }
        finally { if (generator != null) super.setBeanNameGenerator(generator); }
        if (generator != null) ((AnnotationConfigApplicationContext) context).setBeanNameGenerator(generator);
        // Resource fallback loaders must never become the proxy/bean definition class loader.
        context.setClassLoader(nativeClassLoader);
    }

    @Override public void setBeanNameGenerator(BeanNameGenerator generator) {
        super.setBeanNameGenerator(generator);
        beanNameGenerator = generator;
    }

    @Override protected void applyInitializers(ConfigurableApplicationContext context) {
        hooks.initialize().accept((AnnotationConfigApplicationContext) context);
        super.applyInitializers(context);
    }

    @Override protected void afterRefresh(ConfigurableApplicationContext context, ApplicationArguments arguments) {
        hooks.prepared().accept((AnnotationConfigApplicationContext) context);
    }

    void activate(ConfigurableApplicationContext context) {
        hooks.activate().accept((AnnotationConfigApplicationContext) context);
    }

    private static final class NativeContext extends AnnotationConfigApplicationContext {
        private final Runnable beforeClose;
        private final Runnable checkCloseAllowed;
        NativeContext(Runnable beforeClose, Runnable checkCloseAllowed) {
            this.beforeClose = beforeClose;
            this.checkCloseAllowed = checkCloseAllowed;
        }

        @Override protected void doClose() {
            // Rejection must leave both native cleanup and Spring's close state available to the owner.
            checkCloseAllowed.run();
            // Boot ready-listener failures skip failed run listeners; context close is the reliable seam.
            try { beforeClose.run(); }
            finally { super.doClose(); }
        }
    }
}
