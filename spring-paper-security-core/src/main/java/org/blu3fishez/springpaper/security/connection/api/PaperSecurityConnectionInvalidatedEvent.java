package org.blu3fishez.springpaper.security.connection.api;

import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;

/** Local connection has been revoked, even if an external repository cannot currently confirm deletion. */
public record PaperSecurityConnectionInvalidatedEvent(PaperSecurityConnection connection) {}
