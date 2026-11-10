package org.blu3fishez.springpaper.security.connection.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import org.blu3fishez.springpaper.security.connection.spi.PaperAuthenticationEntryPoint;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;

class SecurityDefaultCandidateTest {
    record Consumer(Clock clock, PaperAuthenticationEntryPoint entry) {}
    @Configuration(proxyBeanMethods = false)
    static class Qualified {
        @Bean(defaultCandidate = false) Clock reportingClock() { return Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC); }
        @Bean(defaultCandidate = false) PaperAuthenticationEntryPoint otherEntry() { return session -> CompletableFuture.completedFuture(null); }
        @Bean Consumer consumer(Clock clock, PaperAuthenticationEntryPoint entry) { return new Consumer(clock, entry); }
    }
    @Test void qualifierOnlySecurityCollaboratorsAreNotGeneralDefaults() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(PaperConnectionSecurityConfiguration.class, Qualified.class);
            context.refresh();
            var consumer = context.getBean(Consumer.class);
            assertNotSame(context.getBean("reportingClock"), consumer.clock());
            assertNotSame(context.getBean("otherEntry"), consumer.entry());
            assertTrue(consumer.clock().instant().isAfter(java.time.Instant.EPOCH));
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class SuppliedSettings {
        @Bean PaperSecuritySettings applicationSecuritySettings() {
            return new PaperSecuritySettings(java.time.Duration.ofSeconds(30),
                    java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(2));
        }
    }
    @Test void replacedDefaultsAreRemovedBeforeEvaluatingTheirConfiguration() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("unused",
                    java.util.Map.of("spring.paper.security.pending-timeout-seconds", "not-an-integer")));
            context.register(PaperConnectionSecurityConfiguration.class, SuppliedSettings.class);
            assertDoesNotThrow(context::refresh);
            assertFalse(context.containsBeanDefinition("paperDefaultSecuritySettings"));
            assertEquals(java.time.Duration.ofSeconds(30), context.getBean(PaperSecuritySettings.class).pendingTimeout());
        }
    }
}
