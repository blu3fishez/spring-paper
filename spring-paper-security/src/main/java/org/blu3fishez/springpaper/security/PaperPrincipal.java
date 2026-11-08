package org.blu3fishez.springpaper.security;

import java.security.Principal;
import java.util.UUID;

/** Snapshot of a platform-identified caller, never a replacement for platform authentication. */
public record PaperPrincipal(UUID playerId, String name) implements Principal {
    @Override public String getName() { return name; }
}
