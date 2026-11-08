package org.blu3fishez.springpaper.security.connection.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SecurityLifecycleTest {
    @Configuration(proxyBeanMethods = false)
    static class Consumer {
        @Bean AtomicBoolean consumerCleanupRan() { return new AtomicBoolean(); }
        @Bean ThreadPoolTaskScheduler consumerScheduler() {
            var scheduler = new ThreadPoolTaskScheduler(); scheduler.setDaemon(true); return scheduler;
        }
        @Bean PaperSecuritySettings consumerSettings() {
            return new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1));
        }
        @Bean PaperSecurityResultHandler consumerResults(TaskScheduler scheduler, Clock clock, AtomicBoolean ran) {
            return (session, failure) -> {
                if (session.status() != PaperSecuritySession.Status.CLOSED) return CompletableFuture.completedFuture(null);
                var done = new CompletableFuture<Void>();
                scheduler.schedule(() -> { ran.set(true); done.complete(null); }, clock.instant());
                return done;
            };
        }
        @Bean PaperSecurityFilterChain consumerChain() {
            return new PaperSecurity().addFilter((exchange, next) -> CompletableFuture.completedFuture(PaperSecurityDecision.authenticated(
                    UsernamePasswordAuthenticationToken.authenticated("consumer", null, List.of(new SimpleGrantedAuthority("VERIFIED")))))).build();
        }
    }
    @Test void contextCloseFinishesConsumerSecurityCleanupBeforeStoppingItsScheduler() {
        var context = new AnnotationConfigApplicationContext(Consumer.class, PaperConnectionSecurityConfiguration.class);
        AtomicBoolean ran = context.getBean(AtomicBoolean.class);
        try {
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            context.getBean(PaperConnectionSecurityService.class).open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null))
                    .toCompletableFuture().join();
        } finally { context.close(); }
        assertTrue(ran.get(), "consumer cleanup must finish while its scheduler is still executing tasks");
    }
    @Test void childContextCloseCannotStopTheParentSecurityService() {
        try (var parent = new AnnotationConfigApplicationContext(Consumer.class, PaperConnectionSecurityConfiguration.class)) {
            try (var child = new AnnotationConfigApplicationContext()) {
                child.setParent(parent);
                child.refresh();
            }
            assertTrue(parent.getBean(PaperConnectionSecurityService.class).isRunning());
            var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
            assertEquals(PaperSecuritySession.Status.AUTHENTICATED, parent.getBean(PaperConnectionSecurityService.class)
                    .open(new PaperSecurityRequest(connection, PaperSecurityRequest.Stage.CONNECT, null)).toCompletableFuture().join().status());
        }
    }
}
