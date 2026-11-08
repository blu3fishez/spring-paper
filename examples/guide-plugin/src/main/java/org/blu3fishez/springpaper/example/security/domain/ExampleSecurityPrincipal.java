package org.blu3fishez.springpaper.example.security.domain;

import java.security.Principal;
import java.util.UUID;

public record ExampleSecurityPrincipal(UUID playerId, String name) implements Principal {
    @Override public String getName() { return name; }
}
