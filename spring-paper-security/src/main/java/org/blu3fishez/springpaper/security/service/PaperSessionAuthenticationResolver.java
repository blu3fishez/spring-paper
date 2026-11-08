package org.blu3fishez.springpaper.security.service;

import java.time.Clock;
import org.blu3fishez.springpaper.security.api.PaperSecurityConnections;
import org.blu3fishez.springpaper.security.spi.PaperAuthenticationResolver;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.bukkit.entity.Player;
import org.springframework.security.core.Authentication;

/** In connection mode, a Player object/OP flag alone never establishes authentication. */
public final class PaperSessionAuthenticationResolver implements PaperAuthenticationResolver {
    private final PaperSecurityConnections connections;
    private final Clock clock;
    private final java.util.function.Supplier<PaperConnectionSecurityService> security;
    private final PaperPlatformAuthenticationResolver platform = new PaperPlatformAuthenticationResolver();
    public PaperSessionAuthenticationResolver(PaperSecurityConnections connections, Clock clock,
            java.util.function.Supplier<PaperConnectionSecurityService> security) {
        this.connections = connections; this.clock = clock; this.security = security;
    }
    @Override public Authentication resolve(PaperInvocation invocation) {
        if (!(invocation.caller() instanceof Player player)) return platform.resolve(invocation);
        // 소비자 관찰 이벤트가 실패해도 로컬 철회/재검증을 이전 snapshot으로 우회하지 않게 한다.
        return connections.session(player).filter(session -> session.authorized(clock.instant()) && security.get().current(session))
                .map(session -> session.authentication()).orElse(null);
    }
}
