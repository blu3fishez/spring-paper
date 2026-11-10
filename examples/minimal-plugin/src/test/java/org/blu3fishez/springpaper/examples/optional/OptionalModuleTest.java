package org.blu3fishez.springpaper.examples.optional;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;

class OptionalModuleTest {
    @Configuration(proxyBeanMethods = false) @Import(OptionalModuleConfiguration.class)
    static class DirectImport {}
    static class RequiredConsumer {
        @Bean Integer dependent(@Qualifier("exampleModuleBanner") String banner) { return banner.length(); }
    }
    @Test void absentAndFalseDisableEveryConfigurationEntryPathWhileTrueEnablesIt() {
        for (Boolean enabled : new Boolean[] {null, false, true}) {
            for (String entry : new String[] {"spi", "import", "scan"}) {
                try (var context = context(enabled, entry)) {
                    context.refresh();
                    assertEquals(Boolean.TRUE.equals(enabled), context.containsBean("exampleModuleBanner"), entry);
                    assertEquals(Boolean.TRUE.equals(enabled), context.getBeanNamesForType(OptionalModuleConfiguration.class).length == 1);
                }
            }
        }
    }
    @Test void requiredDependencyOnADisabledFeatureFailsAtStartup() {
        try (var context = context(false, "import")) {
            context.register(RequiredConsumer.class);
            assertThrows(UnsatisfiedDependencyException.class, context::refresh);
        }
    }
    @Test void changingTheEnvironmentAfterRefreshDoesNotDynamicallyRemoveBeans() {
        try (var context = context(true, "import")) {
            context.refresh(); Object banner = context.getBean("exampleModuleBanner");
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("changed",
                    Map.of(ExampleModuleEnabled.PROPERTY, false)));
            assertSame(banner, context.getBean("exampleModuleBanner"));
        }
    }
    @Test void anIndependentUnconditionalBeanDefinitionCanBypassAnOwnersCondition() {
        try (var context = context(false, "import")) {
            context.registerBean("independentBanner", String.class, () -> "consumer explicitly registered this");
            context.refresh();
            assertFalse(context.containsBean("exampleModuleBanner"));
            assertTrue(context.containsBean("independentBanner"));
        }
    }
    private AnnotationConfigApplicationContext context(Boolean enabled, String entry) {
        var context = new AnnotationConfigApplicationContext();
        var values = new HashMap<String, Object>();
        if (enabled != null) values.put(ExampleModuleEnabled.PROPERTY, enabled);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", values));
        switch (entry) {
            case "import" -> context.register(DirectImport.class);
            case "scan" -> context.scan(OptionalModuleConfiguration.class.getPackageName());
            case "spi" -> {
                var module = new OptionalExampleModule();
                if (new PaperConfiguration(context.getEnvironment()).enabled(module.enabledProperty()))
                    context.register(module.configurationClass());
            }
        }
        return context;
    }
}
