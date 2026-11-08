package org.blu3fishez.springpaper.security;

import java.util.LinkedHashSet;
import java.util.List;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.blu3fishez.springpaper.spi.PaperInvocationInterceptor;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Fresh per-invocation identity, always restored; async event permissions require explicit snapshots. */
public final class PaperSecurityInterceptor implements PaperInvocationInterceptor {
    private final String deniedMessage;
    public PaperSecurityInterceptor(String deniedMessage) { this.deniedMessage = deniedMessage; }

    @Override public Object invoke(PaperInvocation invocation, Invocation next) throws Exception {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var caller = invocation.caller();
        if (caller != null && !invocation.asynchronous()) {
            var authorities = new LinkedHashSet<SimpleGrantedAuthority>();
            for (var permission : caller.getEffectivePermissions()) {
                if (permission.getValue()) authorities.add(new SimpleGrantedAuthority(permission.getPermission()));
            }
            if (caller.isOp()) authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
            if (caller instanceof ConsoleCommandSender) authorities.add(new SimpleGrantedAuthority("ROLE_CONSOLE"));
            var id = caller instanceof Player player ? player.getUniqueId() : null;
            var principal = new PaperPrincipal(id, caller.getName());
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
        }
        SecurityContextHolder.setContext(context);
        try { return next.call(); }
        catch (AccessDeniedException denied) {
            if (invocation.kind() == PaperInvocation.Kind.EVENT) throw denied;
            if (invocation.kind() == PaperInvocation.Kind.COMPLETION) return List.of();
            if (caller != null) caller.sendPlainMessage(deniedMessage);
            return true;
        } finally { SecurityContextHolder.setContext(previous); }
    }
}
