package org.blu3fishez.springpaper.security.connection.repository;

import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecurityConnection;
import org.blu3fishez.springpaper.security.connection.domain.PaperSecuritySession;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecuritySessionRepository;

/** Plugin-local default. Consumers replace this bean for shared or durable sessions. */
@RequiredArgsConstructor
public final class InMemoryPaperSecuritySessionRepository implements PaperSecuritySessionRepository {
    private final ConcurrentHashMap<UUID, PaperSecuritySession> sessions = new ConcurrentHashMap<>();
    private final Clock clock;
    @Override public CompletionStage<Boolean> create(PaperSecuritySession initial) {
        if (initial.revision() != 0 || initial.status() != PaperSecuritySession.Status.PENDING || !initial.active(clock.instant())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A fresh pending session is required"));
        }
        return CompletableFuture.completedFuture(sessions.putIfAbsent(initial.connection().connectionId(), initial) == null);
    }
    @Override public CompletionStage<Optional<PaperSecuritySession>> find(PaperSecurityConnection connection) {
        var result = sessions.get(connection.connectionId());
        return CompletableFuture.completedFuture(result != null && result.connection().equals(connection) ? Optional.of(result) : Optional.empty());
    }
    @Override public CompletionStage<Boolean> compareAndSet(PaperSecuritySession expected, PaperSecuritySession replacement) {
        if (!replacement.connection().equals(expected.connection()) || replacement.revision() != Math.incrementExact(expected.revision())) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("CAS must retain identity and increment revision"));
        }
        AtomicBoolean changed = new AtomicBoolean();
        sessions.computeIfPresent(expected.connection().connectionId(), (id, current) -> {
            // 미리 읽은 시각만 검사하면 늦은 콜백이 만료된 인증을 다시 살릴 수 있다.
            // 실제 저장 시점의 시계와 revision을 함께 검사한다. 외부 저장소도 같은 원자적 계약을 구현해야 한다.
            if (sameVersion(current, expected) && current.active(clock.instant())) {
                changed.set(true); return replacement;
            }
            return current;
        });
        return CompletableFuture.completedFuture(changed.get());
    }
    @Override public CompletionStage<Boolean> remove(PaperSecuritySession expected) {
        AtomicBoolean removed = new AtomicBoolean();
        sessions.computeIfPresent(expected.connection().connectionId(), (id, current) -> {
            if (sameVersion(current, expected)) { removed.set(true); return null; }
            return current;
        });
        return CompletableFuture.completedFuture(removed.get());
    }
    @Override public CompletionStage<Optional<PaperSecuritySession>> invalidate(PaperSecurityConnection connection) {
        AtomicReference<PaperSecuritySession> removed = new AtomicReference<>();
        sessions.computeIfPresent(connection.connectionId(), (id, current) -> {
            if (current.connection().equals(connection)) { removed.set(current); return null; }
            return current;
        });
        return CompletableFuture.completedFuture(Optional.ofNullable(removed.get()));
    }
    private static boolean sameVersion(PaperSecuritySession current, PaperSecuritySession expected) {
        return current.connection().equals(expected.connection()) && current.revision() == expected.revision();
    }
}
