package org.blu3fishez.springpaper.pdc;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A read never repairs a holder. Callers decide whether absent or invalid data is acceptable. */
public record PdcReadResult<T>(Status status, Optional<T> value, List<PdcReadProblem> problems) {
    public enum Status { PRESENT, ABSENT, INVALID }
    public PdcReadResult {
        Objects.requireNonNull(status); Objects.requireNonNull(value);
        problems = List.copyOf(problems);
        if ((status == Status.PRESENT) != value.isPresent()
                || (status == Status.INVALID) != !problems.isEmpty()) {
            throw new IllegalArgumentException("Inconsistent PDC read result");
        }
    }
    public static <T> PdcReadResult<T> present(T value) {
        return new PdcReadResult<>(Status.PRESENT, Optional.of(value), List.of());
    }
    public static <T> PdcReadResult<T> absent() {
        return new PdcReadResult<>(Status.ABSENT, Optional.empty(), List.of());
    }
    public static <T> PdcReadResult<T> invalid(List<PdcReadProblem> problems) {
        return new PdcReadResult<>(Status.INVALID, Optional.empty(), problems);
    }
}
