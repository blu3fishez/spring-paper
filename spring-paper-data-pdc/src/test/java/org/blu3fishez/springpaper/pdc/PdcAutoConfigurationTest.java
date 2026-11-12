package org.blu3fishez.springpaper.pdc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.blu3fishez.springpaper.pdc.fixture.valid.BrokerTags;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

class PdcAutoConfigurationTest {
    @Test void disabledDependencyDoesNotRequireANativePlugin() {
        for (Map<String, Object> properties : java.util.List.of(Map.<String, Object>of(),
                Map.<String, Object>of("spring.paper.pdc.enabled", false))) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", properties));
                context.register(Root.class);
                context.refresh();
                assertTrue(context.getBeansOfType(PdcRepositoryFactory.class).isEmpty());
            }
        }
    }

    @Test void enabledImportsCreateTheExistingTypedFactoryWithTheNativeHost() {
        var host = new PdcHost();
        try (var context = nativeContext(host, true, Root.class)) {
            var repository = context.getBean(PdcRepositoryFactory.class).entity(BrokerTags.BrokerData.class);
            UUID owner = UUID.randomUUID();
            repository.write(host.entity, new BrokerTags.BrokerData(12, owner));
            assertEquals(owner, repository.read(host.entity).value().orElseThrow().owner());
        }
    }

    @Test void explicitEnableStillDiscoversRepositoriesWhenTheAutomaticFlagIsFalse() {
        var host = new PdcHost();
        try (var context = nativeContext(host, false, Explicit.class)) {
            assertNotNull(context.getBean(PdcRepositoryFactory.class));
            assertSame(context.getBean(BrokerTags.class), context.getBean(BrokerTags.class));
            assertEquals(1, context.getBeansOfType(PdcRepositoryFactory.class).size());
        }
    }

    private AnnotationConfigApplicationContext nativeContext(PdcHost host, boolean enabled, Class<?> root) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of("spring.paper.pdc.enabled", enabled)));
        context.getBeanFactory().registerSingleton("paperPlugin", host.plugin);
        context.getBeanFactory().registerSingleton("paperThreadAccess", (PaperThreadAccess) () -> host.primary);
        context.register(root);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Root {}

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnablePaperPdcRepositories(basePackageClasses = BrokerTags.class)
    static class Explicit {}
}
