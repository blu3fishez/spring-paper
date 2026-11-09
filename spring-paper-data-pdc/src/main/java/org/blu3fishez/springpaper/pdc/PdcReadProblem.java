package org.blu3fishez.springpaper.pdc;

import java.util.Objects;

/** Data failure, distinct from invalid repository configuration (which fails during bean creation). */
public record PdcReadProblem(String key, Kind kind, String message) {
    public enum Kind { MISSING_FIELD, TYPE_MISMATCH, INVALID_VALUE, INVALID_DOCUMENT }
    public PdcReadProblem {
        Objects.requireNonNull(key); Objects.requireNonNull(kind); Objects.requireNonNull(message);
    }
}
