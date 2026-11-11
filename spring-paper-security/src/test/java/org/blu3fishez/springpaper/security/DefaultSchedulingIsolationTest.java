package org.blu3fishez.springpaper.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.config.PaperConnectionSecurityConfiguration;
import org.blu3fishez.springpaper.security.connection.config.PaperSecurity;
import org.blu3fishez.springpaper.security.connection.config.PaperSecuritySettings;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

class DefaultSchedulingIsolationTest {
    public static class Maintenance {
        final CompletableFuture<String> entered = new CompletableFuture<>();
        final CountDownLatch release = new CountDownLatch(1);
        @Scheduled(initialDelay = 1, fixedDelay = 60000)
        public void maintain() {
            entered.complete(Thread.currentThread().getName());
            try { release.await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }
    @Configuration(proxyBeanMethods = false) @EnableScheduling
    static class Features {
        @Bean Maintenance maintenance() { return new Maintenance(); }
    }
    @Test void securityDeadlinesUseTheirDedicatedTimerWhenTheOnlyOtherSchedulerIsThePaperDefault() throws Exception {
        var plugin = mock(JavaPlugin.class);
        var server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("Isolation");
        when(plugin.namespace()).thenReturn("isolation");
        when(plugin.getDataFolder()).thenReturn(new java.io.File("build/test-data/isolation"));
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("isolation"));
        var expiration = new CompletableFuture<String>();
        try (var context = new AnnotationConfigApplicationContext()) {
            PaperCoreConfiguration.registerHost(context, plugin, new PaperConfiguration(context.getEnvironment()));
            context.register(Features.class, PaperConnectionSecurityConfiguration.class);
            context.registerBean(PaperSecuritySettings.class, () -> new PaperSecuritySettings(
                    Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofSeconds(2)));
            context.registerBean(PaperSecurityFilterChain.class, () -> new PaperSecurity().addFilter((exchange, next) ->
                    CompletableFuture.completedFuture(PaperSecurityDecision.pending(
                            new PaperAuthenticationChallenge("approval", Map.of()), null))).build());
            context.registerBean(PaperSecurityResultHandler.class, () -> (session, failure) -> {
                if (session.status() == PaperSecuritySession.Status.EXPIRED)
                    expiration.complete(Thread.currentThread().getName());
                return CompletableFuture.completedFuture(null);
            });
            context.refresh();
            var maintenance = context.getBean(Maintenance.class);
            try {
                assertTrue(maintenance.entered.get(3, TimeUnit.SECONDS).startsWith("spring-paper-scheduler-"));
                var connection = PaperSecurityConnection.create(java.util.UUID.randomUUID(), "Pending");
                var pending = context.getBean(PaperConnectionSecurityService.class).open(new PaperSecurityRequest(
                        connection, PaperSecurityRequest.Stage.CONNECT, null)).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(PaperSecuritySession.Status.PENDING, pending.status());
                assertTrue(expiration.get(3, TimeUnit.SECONDS).startsWith("spring-paper-security-"));
            } finally { maintenance.release.countDown(); }
        }
    }
}
