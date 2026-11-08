package org.blu3fishez.springpaper.security.connection.spi;

import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;

/** Consumer-owned success, denial, timeout and disconnect effects. Does not run for pending challenges. */
@FunctionalInterface
public interface PaperSecurityResultHandler {
    CompletionStage<Void> handle(PaperSecuritySession result, Throwable failure);
}
