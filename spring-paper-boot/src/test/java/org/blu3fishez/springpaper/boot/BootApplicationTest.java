package org.blu3fishez.springpaper.boot;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.LogManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

class BootApplicationTest {
    @Test void nativePolicySurvivesSpringMainBindingWithoutChangingJulOrRegisteringAJvmHook() throws Exception {
        Object shutdownHook = shutdownHook();
        Field added = shutdownHook.getClass().getDeclaredField("shutdownHookAdded");
        added.setAccessible(true);
        boolean hookWasAdded = ((AtomicBoolean) added.get(shutdownHook)).get();
        var rootLogger = LogManager.getLogManager().getLogger("");
        var handlers = rootLogger.getHandlers();
        var level = rootLogger.getLevel();
        for (String webType : java.util.List.of("servlet", "reactive")) {
            var environment = new StandardEnvironment();
            environment.getPropertySources().addFirst(new MapPropertySource("native-policy-fixture", Map.of(
                    "spring.main.web-application-type", webType,
                    "spring.main.register-shutdown-hook", "true",
                    "spring.main.keep-alive", "true",
                    "spring.main.allow-bean-definition-overriding", "true")));
            var closes = new AtomicInteger();
            var loader = getClass().getClassLoader();
            var application = new BootApplication(Root.class, loader, new DefaultResourceLoader(loader), environment,
                    false, new BootApplication.Hooks(ignored -> {}, ignored -> {}, ignored -> {}, closes::incrementAndGet));
            assertTrue(application.getListeners().stream().noneMatch(LoggingApplicationListener.class::isInstance));
            try (var context = application.run()) {
                assertEquals(WebApplicationType.NONE, application.getWebApplicationType());
                assertInstanceOf(AnnotationConfigApplicationContext.class, context);
                assertFalse(application.isKeepAlive());
                assertFalse(((AnnotationConfigApplicationContext) context).getDefaultListableBeanFactory()
                        .isAllowBeanDefinitionOverriding());
                assertFalse(registered(shutdownHook, context), "A native plugin context must never enter Boot's JVM hook");
            }
            assertEquals(1, closes.get());
        }
        assertEquals(hookWasAdded, ((AtomicBoolean) added.get(shutdownHook)).get());
        assertArrayEquals(handlers, rootLogger.getHandlers());
        assertEquals(level, rootLogger.getLevel());
    }

    @Test void excludesDiscoveredLoggingListenerSubtypesByInstance() {
        assertTrue(new SpringApplication(Root.class).getListeners().stream().anyMatch(LoggingSubclass.class::isInstance),
                "The fixture must expose a logging listener subtype through Spring factories");
        var loader = getClass().getClassLoader();
        var application = new BootApplication(Root.class, loader, new DefaultResourceLoader(loader),
                new StandardEnvironment(), false,
                new BootApplication.Hooks(ignored -> {}, ignored -> {}, ignored -> {}, () -> {}));
        assertTrue(application.getListeners().stream().noneMatch(LoggingApplicationListener.class::isInstance));
    }

    private Object shutdownHook() throws Exception {
        Field field = SpringApplication.class.getDeclaredField("shutdownHook");
        field.setAccessible(true);
        return field.get(null);
    }

    private boolean registered(Object hook, ConfigurableApplicationContext context) throws Exception {
        var method = hook.getClass().getDeclaredMethod("isApplicationContextRegistered", ConfigurableApplicationContext.class);
        method.setAccessible(true);
        return (boolean) method.invoke(hook, context);
    }

    @Configuration(proxyBeanMethods = false)
    static class Root {}

    public static class LoggingSubclass extends LoggingApplicationListener {}
}
