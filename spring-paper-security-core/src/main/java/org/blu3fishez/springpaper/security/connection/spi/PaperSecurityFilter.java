package org.blu3fishez.springpaper.security.connection.spi;

import java.util.concurrent.CompletionStage;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityDecision;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityExchange;

/** Implemented by consumers; invoked by the matching Spring Paper Security chain. */
@FunctionalInterface
public interface PaperSecurityFilter {
    CompletionStage<PaperSecurityDecision> filter(PaperSecurityExchange exchange, Chain next);

    @FunctionalInterface
    interface Chain {
        /** Continue once, retaining the request/session identity and supplying verified authentication as needed. */
        CompletionStage<PaperSecurityDecision> next(PaperSecurityExchange exchange);
    }
}
