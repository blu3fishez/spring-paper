package org.blu3fishez.springpaper.security.connection.config;

import java.time.Duration;
import java.util.Objects;

/** Pending deadline never extends on retries. Authenticated sessions last until explicit invalidation. */
public record PaperSecuritySettings(Duration pendingTimeout, Duration operationTimeout, Duration shutdownTimeout) {
    public PaperSecuritySettings {
        if (Objects.requireNonNull(pendingTimeout).isZero() || pendingTimeout.isNegative()) throw new IllegalArgumentException("Pending timeout must be positive");
        if (Objects.requireNonNull(operationTimeout).isZero() || operationTimeout.isNegative()) throw new IllegalArgumentException("Operation timeout must be positive");
        if (Objects.requireNonNull(shutdownTimeout).isZero() || shutdownTimeout.isNegative()) throw new IllegalArgumentException("Shutdown timeout must be positive");
    }
}
