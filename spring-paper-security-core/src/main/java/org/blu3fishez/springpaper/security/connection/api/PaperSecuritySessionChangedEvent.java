package org.blu3fishez.springpaper.security.connection.api;

import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;

/**
 * Committed active-session transition or local terminal notification, on the completing thread.
 * Terminal notifications do not acknowledge remote deletion; await disconnect/shutdown for that result.
 */
public record PaperSecuritySessionChangedEvent(PaperSecuritySession session) {}
