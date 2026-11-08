package org.blu3fishez.springpaper.security.service;

import java.util.LinkedHashSet;
import org.blu3fishez.springpaper.security.PaperPrincipal;
import org.blu3fishez.springpaper.security.spi.PaperAuthenticationResolver;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Default for platform-only method security. Its identity is not proof of an extra login/account-link flow. */
public final class PaperPlatformAuthenticationResolver implements PaperAuthenticationResolver {
    @Override public Authentication resolve(PaperInvocation invocation) {
        var caller = invocation.caller();
        if (caller == null || invocation.asynchronous()) return null;
        var authorities = new LinkedHashSet<SimpleGrantedAuthority>();
        for (var permission : caller.getEffectivePermissions()) {
            if (permission.getValue()) authorities.add(new SimpleGrantedAuthority(permission.getPermission()));
        }
        if (caller.isOp()) authorities.add(new SimpleGrantedAuthority("ROLE_OPERATOR"));
        if (caller instanceof ConsoleCommandSender) authorities.add(new SimpleGrantedAuthority("ROLE_CONSOLE"));
        var id = caller instanceof Player player ? player.getUniqueId() : null;
        return UsernamePasswordAuthenticationToken.authenticated(new PaperPrincipal(id, caller.getName()), null, authorities);
    }
}
