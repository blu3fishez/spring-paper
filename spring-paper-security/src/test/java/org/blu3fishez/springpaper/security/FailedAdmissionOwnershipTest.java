package org.blu3fishez.springpaper.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.papermc.paper.connection.PlayerLoginConnection;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.security.api.PaperSecurityConnections;
import org.blu3fishez.springpaper.security.connection.config.PaperSecuritySettings;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.listener.PaperConnectionSecurityListener;
import org.blu3fishez.springpaper.security.spi.PaperConnectionAdmissionHandler;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;

class FailedAdmissionOwnershipTest {
    @Test void rejectedCreateIsPreservedWhenTheRealPaperAdmissionAdapterReleasesTheFailedLogin() {
        var clock = Clock.systemUTC();
        var repository = new InMemoryPaperSecuritySessionRepository(clock);
        var connection = PaperSecurityConnection.create(UUID.randomUUID(), "Consumer");
        var existing = PaperSecuritySession.pending(connection, clock.instant().plusSeconds(100));
        assertTrue(repository.create(existing).toCompletableFuture().join());
        try (var timer = Executors.newSingleThreadScheduledExecutor();
                var security = new PaperConnectionSecurityService(repository, List.of(), clock, new ConcurrentTaskScheduler(timer),
                        new PaperSecuritySettings(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(1)),
                        pending -> CompletableFuture.completedFuture(null), (session, failure) -> CompletableFuture.completedFuture(null), event -> {})) {
            var plugin = mock(JavaPlugin.class);
            when(plugin.getLogger()).thenReturn(Logger.getLogger("failed-admission-ownership"));
            var connections = mock(PaperSecurityConnections.class);
            var transport = mock(PlayerLoginConnection.class);
            when(connections.begin(connection.playerId(), connection.name(), transport)).thenReturn(connection);
            var listener = new PaperConnectionSecurityListener(plugin, mock(PaperTasks.class), security, connections,
                    event -> null, mock(PaperConnectionAdmissionHandler.class), new PaperConfiguration(new StandardEnvironment()));
            var login = mock(AsyncPlayerPreLoginEvent.class);
            when(login.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
            when(login.getUniqueId()).thenReturn(connection.playerId());
            when(login.getName()).thenReturn(connection.name());
            when(login.getConnection()).thenReturn(transport);
            when(login.getAddress()).thenReturn(InetAddress.getLoopbackAddress());

            listener.preLogin(login);

            verify(connections).forget(connection);
            verify(login).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(Component.class));
            assertEquals(existing, repository.find(connection).toCompletableFuture().join().orElseThrow());
        }
        assertEquals(existing, repository.find(connection).toCompletableFuture().join().orElseThrow());
    }
}
