package org.blu3fishez.springpaper.security;

import static org.junit.jupiter.api.Assertions.*;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.security.spi.PaperAuthenticationResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;

class QualifiedDefaultsTest {
    record Consumer(PaperAuthenticationResolver resolver) {}
    @Configuration(proxyBeanMethods = false)
    static class Qualified {
        @Bean(defaultCandidate = false) PaperAuthenticationResolver reportingResolver() { return invocation -> null; }
        @Bean Consumer consumer(PaperAuthenticationResolver resolver) { return new Consumer(resolver); }
    }
    @Test void qualifiedHostResolverDoesNotSuppressThePlatformDefault() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.register(SecurityConfiguration.class, Qualified.class);
            context.refresh();
            assertNotSame(context.getBean("reportingResolver"), context.getBean(Consumer.class).resolver());
        }
    }
}
