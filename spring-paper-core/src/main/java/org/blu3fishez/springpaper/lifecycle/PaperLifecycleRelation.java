package org.blu3fishez.springpaper.lifecycle;

import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;

/**
 * An immutable relationship between two Spring-managed Paper lifecycle owners.
 * Declare it as an ordinary Spring bean; injection, qualifiers and aliases select the exact owners.
 * It neither starts nor owns its endpoints. Both endpoints must expose the selected lifecycle hook.
 */
@Getter(AccessLevel.PACKAGE)
@Accessors(fluent = true)
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class PaperLifecycleRelation {
    enum Stage { STARTUP, SHUTDOWN }
    private final Stage stage;
    private final Object first;
    private final Object next;

    /** Start first before next. Unrelated owners need no relationship. */
    public static PaperLifecycleRelation startupBefore(Object first, Object next) {
        return new PaperLifecycleRelation(Stage.STARTUP, Objects.requireNonNull(first), Objects.requireNonNull(next));
    }

    /** Stop first before next, independently of their start order. */
    public static PaperLifecycleRelation shutdownBefore(Object first, Object next) {
        return new PaperLifecycleRelation(Stage.SHUTDOWN, Objects.requireNonNull(first), Objects.requireNonNull(next));
    }
}
