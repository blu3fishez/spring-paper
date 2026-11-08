package org.blu3fishez.springpaper.security;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.security.api.PaperSecurityConnections;
import org.blu3fishez.springpaper.security.listener.PaperConnectionSecurityListener;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.service.*;
import org.blu3fishez.springpaper.security.spi.*;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Defaults back off by contract type, independently of consumer bean names. */
@Configuration(proxyBeanMethods = false)
public class PaperSecurityDefaults {
    @Bean public static BeanFactoryPostProcessor paperSecurityInfrastructure(PaperConfiguration config) {
        return beans -> {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beans;
            fallback(beans, registry, PaperAccessDeniedHandler.class, "paperAccessDeniedHandler",
                    () -> new DefaultPaperAccessDeniedHandler(config.text("spring.paper.security.denied-message", "You do not have permission to do that.")));
            fallback(beans, registry, PaperSecurityConnections.class, "paperSecurityConnections",
                    () -> new PaperSecurityConnections(beans.getBean(Clock.class), Duration.ofSeconds(
                            config.integer("spring.paper.security.connection.snapshot-ttl-seconds", 5))));
            boolean connectionMode = config.enabled("spring.paper.security.connection.enabled");
            fallback(beans, registry, PaperAuthenticationResolver.class, "paperAuthenticationResolver",
                    () -> connectionMode ? new PaperSessionAuthenticationResolver(beans.getBean(PaperSecurityConnections.class), beans.getBean(Clock.class),
                            () -> beans.getBean(PaperConnectionSecurityService.class))
                            : new PaperPlatformAuthenticationResolver());
            if (beans.getBeanNamesForType(PaperSecurityInterceptor.class, false, false).length == 0) {
                var definition = new RootBeanDefinition(PaperSecurityInterceptor.class);
                definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                registry.registerBeanDefinition("paperSecurityInterceptor", definition);
            }
            if (connectionMode) {
                fallback(beans, registry, PaperConnectionAuthenticationConverter.class, "paperConnectionAuthenticationConverter", () -> event -> null);
                fallback(beans, registry, PaperConnectionAdmissionHandler.class, "paperConnectionAdmissionHandler",
                        () -> new DefaultPaperConnectionAdmissionHandler(config.text("spring.paper.security.denied-message", "Authentication is required or unavailable.")));
                if (beans.getBeanNamesForType(PaperConnectionSecurityListener.class, false, false).length == 0) {
                    var definition = new RootBeanDefinition(PaperConnectionSecurityListener.class);
                    definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                    registry.registerBeanDefinition("paperConnectionSecurityListener", definition);
                }
            }
        };
    }
    private static <T> void fallback(ConfigurableListableBeanFactory beans, BeanDefinitionRegistry registry,
                                    Class<T> type, String name, Supplier<T> factory) {
        if (beans.getBeanNamesForType(type, false, false).length == 0) registry.registerBeanDefinition(name, new RootBeanDefinition(type, factory));
    }
}
