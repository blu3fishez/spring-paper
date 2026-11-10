package org.blu3fishez.springpaper.security;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.config.internal.DefaultBeans;
import org.blu3fishez.springpaper.security.api.PaperSecurityConnections;
import org.blu3fishez.springpaper.security.listener.PaperConnectionSecurityListener;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.service.*;
import org.blu3fishez.springpaper.security.spi.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Lazy;

/** Defaults back off by contract type, independently of consumer bean names. */
@Configuration(proxyBeanMethods = false)
public class PaperSecurityDefaults {
    @Bean public static BeanFactoryPostProcessor paperSecurityInfrastructure(PaperConfiguration config) {
        return beans -> {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beans;
            DefaultBeans.backOff(beans, PaperAccessDeniedHandler.class, "paperDefaultAccessDeniedHandler", "paperAccessDeniedHandler");
            DefaultBeans.backOff(beans, PaperSecurityConnections.class, "paperDefaultSecurityConnections", "paperSecurityConnections");
            DefaultBeans.backOff(beans, PaperAuthenticationResolver.class, "paperDefaultAuthenticationResolver", "paperAuthenticationResolver");
            DefaultBeans.backOff(beans, PaperSecurityInterceptor.class, "paperDefaultSecurityInterceptor", "paperSecurityInterceptor");
            boolean connectionMode = config.enabled("spring.paper.security.connection.enabled");
            if (connectionMode) {
                fallback(beans, registry, PaperConnectionAuthenticationConverter.class, "paperConnectionAuthenticationConverter", () -> event -> null);
                fallback(beans, registry, PaperConnectionAdmissionHandler.class, "paperConnectionAdmissionHandler",
                        () -> new DefaultPaperConnectionAdmissionHandler(config.text("spring.paper.security.denied-message", "Authentication is required or unavailable.")));
                if (DefaultBeans.candidates(beans, PaperConnectionSecurityListener.class).isEmpty()) {
                    var definition = new RootBeanDefinition(PaperConnectionSecurityListener.class);
                    definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                    registry.registerBeanDefinition("paperConnectionSecurityListener", definition);
                }
            }
        };
    }
    @Bean @Lazy @Fallback
    public PaperAccessDeniedHandler paperDefaultAccessDeniedHandler(PaperConfiguration config) {
        return new DefaultPaperAccessDeniedHandler(config.text("spring.paper.security.denied-message", "You do not have permission to do that."));
    }
    @Bean @Lazy @Fallback
    public PaperSecurityConnections paperDefaultSecurityConnections(Clock clock, PaperConfiguration config) {
        return new PaperSecurityConnections(clock,
                Duration.ofSeconds(config.integer("spring.paper.security.connection.snapshot-ttl-seconds", 5)));
    }
    @Bean @Lazy @Fallback
    public PaperAuthenticationResolver paperDefaultAuthenticationResolver(PaperConfiguration config,
            ObjectProvider<PaperSecurityConnections> connections, ObjectProvider<Clock> clock,
            ObjectProvider<PaperConnectionSecurityService> service) {
        return config.enabled("spring.paper.security.connection.enabled")
                ? new PaperSessionAuthenticationResolver(connections.getObject(), clock.getObject(), service::getObject)
                : new PaperPlatformAuthenticationResolver();
    }
    @Bean @Lazy @Fallback
    public PaperSecurityInterceptor paperDefaultSecurityInterceptor(PaperAuthenticationResolver resolver, PaperAccessDeniedHandler denied) {
        return new PaperSecurityInterceptor(resolver, denied);
    }
    private static <T> void fallback(ConfigurableListableBeanFactory beans, BeanDefinitionRegistry registry,
                                    Class<T> type, String name, Supplier<T> factory) {
        if (DefaultBeans.candidates(beans, type).isEmpty()) registry.registerBeanDefinition(name, new RootBeanDefinition(type, factory));
    }
}
