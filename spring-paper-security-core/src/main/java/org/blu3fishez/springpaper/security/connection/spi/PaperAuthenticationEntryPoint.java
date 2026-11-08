package org.blu3fishez.springpaper.security.connection.spi;

import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;

/** Consumer-owned pending flow. Schedule transport changes on its thread and recheck connection identity. */
@FunctionalInterface
public interface PaperAuthenticationEntryPoint {
    CompletionStage<Void> commence(PaperSecuritySession pending);
}
