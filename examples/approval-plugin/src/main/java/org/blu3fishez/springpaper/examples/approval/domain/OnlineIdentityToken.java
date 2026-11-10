package org.blu3fishez.springpaper.examples.approval.domain;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Trusted transport evidence, created after Paper's online-mode verification, never from a player command. */
public final class OnlineIdentityToken extends AbstractAuthenticationToken {
    public record Identity(UUID playerId, String name) implements Principal {
        @Override public String getName() { return name; }
    }
    private final Identity identity;
    public OnlineIdentityToken(UUID playerId, String name) {
        super(List.of()); identity = new Identity(playerId, name);
    }
    @Override public Identity getPrincipal() { return identity; }
    @Override public Object getCredentials() { return "paper-online-mode"; }
}
