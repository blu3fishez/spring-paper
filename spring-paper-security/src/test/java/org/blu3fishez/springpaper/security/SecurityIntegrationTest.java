package org.blu3fishez.springpaper.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityIntegrationTest {
    public static class ProtectedAction {
        @PreAuthorize("hasRole('CONSOLE')") public String consoleOnly() { return "allowed"; }
    }
    @Test void methodSecurityUsesPerCallerContextAndAlwaysRestoresIt() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of()));
            context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
            context.registerBean(ProtectedAction.class);
            context.register(SecurityConfiguration.class);
            context.refresh();
            var interceptor = context.getBean(PaperSecurityInterceptor.class);
            var action = context.getBean(ProtectedAction.class);
            var previous = SecurityContextHolder.getContext();
            ConsoleCommandSender console = mock(ConsoleCommandSender.class);
            when(console.getEffectivePermissions()).thenReturn(Set.of());
            when(console.getName()).thenReturn("CONSOLE");
            assertEquals("allowed", interceptor.invoke(new PaperInvocation(console, "test", false, PaperInvocation.Kind.COMMAND), action::consoleOnly));
            assertSame(previous, SecurityContextHolder.getContext());
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.getName()).thenReturn("Player");
            when(player.getEffectivePermissions()).thenReturn(Set.of());
            assertEquals(true, interceptor.invoke(new PaperInvocation(player, "test", false, PaperInvocation.Kind.COMMAND), action::consoleOnly));
            verify(player).sendPlainMessage(anyString());
            assertSame(previous, SecurityContextHolder.getContext());
            assertThrows(IllegalStateException.class, () -> interceptor.invoke(
                    new PaperInvocation(console, "test", false, PaperInvocation.Kind.COMMAND), () -> { throw new IllegalStateException("failure"); }));
            assertSame(previous, SecurityContextHolder.getContext());
        } finally { SecurityContextHolder.clearContext(); }
    }
    @Test void asynchronousEventsDoNotReadMutablePlayerPermissions() throws Exception {
        Player player = mock(Player.class);
        var interceptor = new PaperSecurityInterceptor("denied");
        assertNull(interceptor.invoke(new PaperInvocation(player, "async", true, PaperInvocation.Kind.EVENT),
                () -> SecurityContextHolder.getContext().getAuthentication()));
        verifyNoInteractions(player);
    }
}
