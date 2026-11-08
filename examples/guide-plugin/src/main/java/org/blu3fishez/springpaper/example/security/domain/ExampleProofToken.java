package org.blu3fishez.springpaper.example.security.domain;

import java.util.List;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/** Synthetic integration-verification proof; not a Minecraft or Discord authentication mechanism. */
public final class ExampleProofToken extends AbstractAuthenticationToken {
    private final PaperSecurityConnection connection;
    private final boolean confirmation;
    private String response;
    public ExampleProofToken(PaperSecurityConnection connection, boolean confirmation, String response) {
        super(List.of()); this.connection = connection; this.confirmation = confirmation; this.response = response;
    }
    public PaperSecurityConnection connection() { return connection; }
    public boolean confirmation() { return confirmation; }
    @Override public Object getPrincipal() { return connection.playerId(); }
    @Override public Object getCredentials() { return response; }
    @Override public void eraseCredentials() { response = null; }
}
