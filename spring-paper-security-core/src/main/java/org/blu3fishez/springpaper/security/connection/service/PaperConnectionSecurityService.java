package org.blu3fishez.springpaper.security.connection.service;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.Objects;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityConnectionInvalidatedEvent;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.api.PaperSecuritySessionChangedEvent;
import org.blu3fishez.springpaper.security.connection.config.PaperSecuritySettings;
import org.blu3fishez.springpaper.security.connection.domain.*;
import org.blu3fishez.springpaper.security.connection.internal.ContextClassLoaderScope;
import org.blu3fishez.springpaper.security.connection.spi.*;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;

/**
 * Connection-security API for transport adapters, commands and verified external callbacks.
 * Consumers own proof validation, storage I/O and transport effects; this service owns transitions/deadlines.
 * Extension calls must return stages promptly. Authentication travels as a value, not a ThreadLocal.
 */
public final class PaperConnectionSecurityService implements AutoCloseable, BeanClassLoaderAware, SmartLifecycle,
        ApplicationContextAware, ApplicationListener<ContextClosedEvent>, Ordered {
    private final PaperSecuritySessionRepository repository;
    private final List<PaperSecurityFilterChain> chains;
    private final Clock clock;
    private final TaskScheduler scheduler;
    private final PaperSecuritySettings settings;
    private final PaperAuthenticationEntryPoint entryPoint;
    private final PaperSecurityResultHandler results;
    private final ApplicationEventPublisher events;
    private final Object admissionLock = new Object();
    private final ConcurrentHashMap<UUID, Managed> managed = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Operation> operations = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> cleanups = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private CompletableFuture<Void> shutdownResult;
    private ClassLoader loader = ContextClassLoaderScope.capture();
    private ApplicationContext context;

    public PaperConnectionSecurityService(PaperSecuritySessionRepository repository, List<PaperSecurityFilterChain> chains,
            Clock clock, TaskScheduler scheduler, PaperSecuritySettings settings, PaperAuthenticationEntryPoint entryPoint,
            PaperSecurityResultHandler results, ApplicationEventPublisher events) {
        this.repository = Objects.requireNonNull(repository); this.chains = List.copyOf(chains);
        this.clock = Objects.requireNonNull(clock); this.scheduler = Objects.requireNonNull(scheduler);
        this.settings = Objects.requireNonNull(settings); this.entryPoint = Objects.requireNonNull(entryPoint);
        this.results = Objects.requireNonNull(results); this.events = Objects.requireNonNull(events);
    }
    @Override public void setBeanClassLoader(ClassLoader loader) { this.loader = loader; }
    @Override public void setApplicationContext(ApplicationContext context) { this.context = context; }

    /** Nonblocking local invalidation fence. True does not replace authoritative reads or snapshot freshness checks. */
    public boolean current(PaperSecuritySession snapshot) {
        Managed owner = owner(snapshot.connection());
        PaperSecuritySession current = owner == null ? null : owner.snapshot;
        return !closed && current != null && current.revision() == snapshot.revision()
                && current.status() == snapshot.status() && snapshot.active(clock.instant());
    }

    /** Open exactly once per transport connection; connection ids must never be reused. */
    public CompletionStage<PaperSecuritySession> open(PaperSecurityRequest request) {
        PaperSecuritySession initial;
        try { initial = PaperSecuritySession.pending(request.connection(), clock.instant().plus(settings.pendingTimeout())); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        Managed owner = new Managed(initial);
        Operation operation;
        synchronized (admissionLock) {
            if (closed) return stopped();
            if (operations.containsKey(request.connection().connectionId()) || managed.putIfAbsent(request.connection().connectionId(), owner) != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("Connection is already open"));
            }
            operation = register(owner);
        }
        scheduleOperation(operation);
        if (operation.result.isDone()) return operation.result.minimalCompletionStage();
        stage(() -> repository.create(initial)).thenCompose(created -> {
            if (!created) {
                // create=false means we never owned the existing row. Failed-open cleanup must not delete it.
                synchronized (admissionLock) { managed.remove(owner.connection.connectionId(), owner); }
                complete(operation, null, new IllegalStateException("Connection id already exists in the repository"));
                return stale();
            }
            if (!active(operation)) return discard(owner.connection);
            schedulePending(owner);
            return evaluate(request, initial, operation);
        }).whenComplete((session, failure) -> finish(operation, session, failure));
        return operation.result.minimalCompletionStage();
    }

    /** Continue this open connection. One evaluation may run at a time; pending retries keep their deadline. */
    public CompletionStage<PaperSecuritySession> authenticate(PaperSecurityRequest request) {
        Operation operation;
        synchronized (admissionLock) {
            if (closed) return stopped();
            Managed owner = owner(request.connection());
            if (owner == null || !owner.snapshot.active(clock.instant())) return stale();
            operation = register(owner);
        }
        scheduleOperation(operation);
        if (operation.result.isDone()) return operation.result.minimalCompletionStage();
        session(request.connection()).thenCompose(found -> {
            var current = found.orElseThrow(() -> new CancellationException("Security session no longer exists"));
            if (!current.active(clock.instant())) return stale();
            return evaluate(request, current, operation);
        }).whenComplete((session, failure) -> finish(operation, session, failure));
        return operation.result.minimalCompletionStage();
    }

    /** Authoritative asynchronous read; invocation-thread identity resolvers must use bounded snapshots. */
    public CompletionStage<Optional<PaperSecuritySession>> session(PaperSecurityConnection connection) {
        Managed owner = owner(connection);
        if (closed || owner == null) return CompletableFuture.completedFuture(Optional.empty());
        return stage(() -> repository.find(connection)).thenCompose(found -> {
            if (owner(connection) != owner) return CompletableFuture.completedFuture(Optional.empty());
            if (found.isPresent()) {
                var current = found.get();
                if (!current.connection().equals(connection)) throw new IllegalStateException("Repository returned another connection");
                if (!current.active(clock.instant()) && (current.status() == PaperSecuritySession.Status.PENDING
                        || current.status() == PaperSecuritySession.Status.AUTHENTICATED)) {
                    return invalidate(connection, PaperSecuritySession.Status.EXPIRED, new TimeoutException("Security session expired"), candidate -> candidate == owner)
                            .handle((ignored, failure) -> Optional.<PaperSecuritySession>empty());
                }
                synchronized (admissionLock) {
                    if (owner(connection) == owner && current.revision() >= owner.snapshot.revision()) {
                        owner.snapshot = current;
                        if (current.status() != PaperSecuritySession.Status.PENDING) cancelPending(owner);
                    }
                }
            }
            return CompletableFuture.completedFuture(found);
        });
    }

    /** Local revocation happens immediately. The returned stage acknowledges repository/handler cleanup. */
    public CompletionStage<Void> disconnect(PaperSecurityConnection connection) {
        return invalidate(connection, PaperSecuritySession.Status.CLOSED, new CancellationException("Connection disconnected"), candidate -> true);
    }

    private CompletionStage<PaperSecuritySession> evaluate(PaperSecurityRequest request, PaperSecuritySession current, Operation operation) {
        if (!active(operation)) return discard(request.connection());
        var exchange = new PaperSecurityExchange(request, current, current.authentication());
        // FilterChainProxy처럼 첫 매칭 체인 하나만 실행한다. 미일치는 명시적 거절이다.
        var selected = chains.stream().filter(chain -> chain.matches(request)).findFirst().orElse(null);
        CompletionStage<PaperSecurityDecision> decision = selected == null
                ? CompletableFuture.completedFuture(PaperSecurityDecision.denied("no_matching_chain")) : selected.filter(exchange);
        return decision.handle((outcome, failure) -> {
            if (failure == null) return new Evaluation(outcome, null);
            var cause = unwrap(failure);
            if (cause instanceof AuthenticationException) return new Evaluation(PaperSecurityDecision.denied("authentication_failed"), cause);
            if (cause instanceof AccessDeniedException) return new Evaluation(PaperSecurityDecision.denied("access_denied"), cause);
            throw new CompletionException(cause);
        }).thenCompose(evaluation -> {
            if (!active(operation)) return discard(request.connection());
            var outcome = evaluation.decision;
            var status = switch (outcome.status()) {
                case AUTHENTICATED -> PaperSecuritySession.Status.AUTHENTICATED;
                case PENDING -> PaperSecuritySession.Status.PENDING;
                case DENIED -> PaperSecuritySession.Status.DENIED;
            };
            var replacement = new PaperSecuritySession(current.connection(), Math.incrementExact(current.revision()), status,
                    outcome.authentication(), outcome.challenge(), status == PaperSecuritySession.Status.AUTHENTICATED ? Instant.MAX : current.expiresAt(), outcome.reason());
            return stage(() -> repository.compareAndSet(current, replacement)).thenCompose(changed -> {
                if (!changed) return stale();
                boolean accepted;
                synchronized (admissionLock) {
                    // CAS와 ACK 사이에 disconnect/timeout이 발생했으면 인증을 다시 공개하지 않는다.
                    accepted = active(operation);
                    if (accepted) {
                        operation.owner.snapshot = replacement;
                        if (status != PaperSecuritySession.Status.PENDING) cancelPending(operation.owner);
                    }
                }
                if (!accepted) return discard(request.connection());
                return notify(replacement, evaluation.failure).thenApply(ignored -> replacement);
            });
        });
    }

    private Operation register(Managed owner) {
        var operation = new Operation(owner);
        if (operations.putIfAbsent(owner.connection.connectionId(), operation) != null) {
            operation.result.completeExceptionally(new IllegalStateException("An authentication operation is already running"));
            return operation;
        }
        return operation;
    }
    private void scheduleOperation(Operation operation) {
        if (operation.result.isDone()) return;
        try {
            operation.timeout = scheduler.schedule(() -> invalidate(operation.owner.connection, PaperSecuritySession.Status.EXPIRED,
                    new TimeoutException("Authentication operation timed out"), candidate -> candidate == operation.owner
                            && operations.get(candidate.connection.connectionId()) == operation && !operation.result.isDone()),
                    clock.instant().plus(settings.operationTimeout()));
            if (operation.result.isDone() && operation.timeout != null) operation.timeout.cancel(false);
        } catch (RuntimeException failure) {
            invalidate(operation.owner.connection, PaperSecuritySession.Status.DENIED, failure, candidate -> candidate == operation.owner);
        }
    }
    private void schedulePending(Managed owner) {
        var timer = scheduler.schedule(() -> invalidate(owner.connection, PaperSecuritySession.Status.EXPIRED,
                new TimeoutException("Authentication session expired"), candidate -> candidate == owner
                        && candidate.snapshot.status() == PaperSecuritySession.Status.PENDING), owner.snapshot.expiresAt());
        synchronized (admissionLock) {
            if (owner(owner.connection) == owner && owner.snapshot.status() == PaperSecuritySession.Status.PENDING) owner.pendingTimer = timer;
            else if (timer != null) timer.cancel(false);
        }
    }

    private CompletionStage<Void> invalidate(PaperSecurityConnection connection, PaperSecuritySession.Status terminal,
                                             Throwable failure, Predicate<Managed> condition) {
        Managed revoked;
        Operation operation;
        var cleanup = new CompletableFuture<Void>();
        synchronized (admissionLock) {
            revoked = owner(connection);
            if (!condition.test(revoked)) return CompletableFuture.completedFuture(null);
            if (revoked != null) { managed.remove(connection.connectionId(), revoked); cancelPending(revoked); }
            operation = operations.get(connection.connectionId());
            cleanups.add(cleanup);
        }
        // 로컬 차단은 원격 삭제의 ACK와 독립적이다. 삭제 stage가 멈춰도 인증 대기 future는 종료되어야 한다.
        // 소비자 publisher/handler/저장소 호출과 future 완료를 전역 admission lock 안에서 실행하지 않는다.
        if (revoked != null) {
            try { ContextClassLoaderScope.call(loader, () -> { events.publishEvent(new PaperSecurityConnectionInvalidatedEvent(connection)); return null; }); }
            catch (RuntimeException observerFailure) { suppress(failure, observerFailure); }
        }
        if (operation != null && operation.owner == revoked) complete(operation, null, failure);
        CompletionStage<Void> notification = revoked == null ? CompletableFuture.completedFuture(null)
                : notify(revoked.snapshot.finish(terminal, clock.instant()), terminal == PaperSecuritySession.Status.CLOSED ? null : failure);
        CompletionStage<Void> removal = stage(() -> repository.invalidate(connection)).thenApply(ignored -> null);
        CompletableFuture.allOf(notification.toCompletableFuture(), removal.toCompletableFuture()).whenComplete((ignored, cleanupFailure) -> {
            if (cleanupFailure == null) cleanup.complete(null);
            else { suppress(failure, unwrap(cleanupFailure)); cleanup.completeExceptionally(unwrap(cleanupFailure)); }
            cleanups.remove(cleanup);
        });
        return cleanup.minimalCompletionStage();
    }
    private CompletionStage<Void> notify(PaperSecuritySession session, Throwable failure) {
        // 추가 검증은 filter에서 끝내야 한다. 결과 handler는 이미 정해진 상태의 부작용을 처리하며 재호출에 안전해야 한다.
        return stage(() -> {
            events.publishEvent(new PaperSecuritySessionChangedEvent(session));
            return session.status() == PaperSecuritySession.Status.PENDING && session.challenge() != null
                    ? entryPoint.commence(session) : results.handle(session, failure);
        });
    }
    private void finish(Operation operation, PaperSecuritySession session, Throwable failure) {
        if (failure == null) { complete(operation, session, null); return; }
        if (active(operation)) invalidate(operation.owner.connection, PaperSecuritySession.Status.DENIED, unwrap(failure), candidate -> candidate == operation.owner);
        else complete(operation, null, unwrap(failure));
    }
    private void complete(Operation operation, PaperSecuritySession session, Throwable failure) {
        Throwable outcome = failure;
        synchronized (admissionLock) {
            if (outcome == null && (!active(operation) || operations.get(operation.owner.connection.connectionId()) != operation)) {
                outcome = new CancellationException("Security result is no longer current");
            }
            operations.remove(operation.owner.connection.connectionId(), operation);
        }
        if (operation.timeout != null) operation.timeout.cancel(false);
        // 실제 Paper의 비동기 thenCompose 소비에서 정리 whenComplete보다 다음 인증 요청이 먼저 실행됐다.
        // public future 완료는 소비자 코드를 즉시 실행할 수 있으므로 내부 슬롯/타이머를 먼저 해제한다.
        if (outcome == null) operation.result.complete(session); else operation.result.completeExceptionally(outcome);
    }
    private CompletionStage<PaperSecuritySession> discard(PaperSecurityConnection connection) {
        return disconnect(connection).handle((ignored, failure) -> null).thenCompose(ignored -> stale());
    }
    private boolean active(Operation operation) { return !closed && !operation.result.isDone() && owner(operation.owner.connection) == operation.owner; }
    private Managed owner(PaperSecurityConnection connection) {
        var owner = managed.get(connection.connectionId());
        return owner != null && owner.connection.equals(connection) ? owner : null;
    }
    private static void cancelPending(Managed owner) {
        var timer = owner.pendingTimer;
        if (timer != null) timer.cancel(false);
    }
    public CompletionStage<Void> shutdown() {
        CompletableFuture<Void> result;
        List<PaperSecurityConnection> connections;
        synchronized (admissionLock) {
            if (shutdownResult != null) return shutdownResult.minimalCompletionStage();
            closed = true;
            result = shutdownResult = new CompletableFuture<>();
            connections = managed.values().stream().map(owner -> owner.connection).toList();
        }
        connections.forEach(this::disconnect);
        CompletableFuture.allOf(cleanups.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
            if (failure == null) result.complete(null); else result.completeExceptionally(unwrap(failure));
        });
        return result.minimalCompletionStage();
    }
    @Override public void close() {
        try { shutdown().toCompletableFuture().get(settings.shutdownTimeout().toMillis(), TimeUnit.MILLISECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); failShutdown(failure); throw new IllegalStateException("Security shutdown interrupted", failure); }
        catch (Exception failure) { failShutdown(failure); throw new IllegalStateException("Security shutdown did not finish", failure); }
    }
    private void failShutdown(Throwable failure) {
        synchronized (admissionLock) { if (shutdownResult != null) shutdownResult.completeExceptionally(failure); }
    }
    // 회귀 검증: phase만 높여도 ExecutorConfigurationSupport는 ContextClosedEvent에서 먼저 새 작업을 거절했다.
    // 이 컨텍스트의 close 신호를 동기/우선 처리해 소비자 cleanup을 끝내고, 일반 stop은 역 phase 계약에 참여한다.
    // 소비자 executor의 acceptTasksAfterContextClose 설정을 전역으로 바꾸지 않는다.
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public boolean supportsAsyncExecution() { return false; }
    @Override public void onApplicationEvent(ContextClosedEvent event) {
        if (event.getApplicationContext() == context) close();
    }
    @Override public int getPhase() { return SmartLifecycle.DEFAULT_PHASE; }
    @Override public boolean isRunning() { return !closed; }
    @Override public void start() { if (closed) throw new IllegalStateException("A closed security context cannot restart"); }
    @Override public void stop() { close(); }
    @Override public void stop(Runnable callback) {
        try { close(); } finally { callback.run(); }
    }
    private <T> CompletionStage<T> stage(Supplier<CompletionStage<T>> action) {
        // 외부 실행기의 완료 스레드에서도 소비자 코드를 호출하는 범위의 plugin TCCL을 설정·복원한다.
        try { return ContextClassLoaderScope.call(loader, () -> java.util.Objects.requireNonNull(action.get(), "A security extension returned a null stage")); }
        catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
    }
    private static <T> CompletionStage<T> stale() { return CompletableFuture.failedFuture(new CancellationException("Stale security connection or revision")); }
    private static <T> CompletionStage<T> stopped() { return CompletableFuture.failedFuture(new IllegalStateException("Spring Paper Security is stopping")); }
    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException) && failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
    private static void suppress(Throwable failure, Throwable additional) { if (failure != additional) failure.addSuppressed(additional); }
    private static final class Managed {
        final PaperSecurityConnection connection;
        volatile PaperSecuritySession snapshot; // Pending/last acknowledged metadata for revocation notices, never an authority source.
        volatile ScheduledFuture<?> pendingTimer;
        Managed(PaperSecuritySession snapshot) { this.connection = snapshot.connection(); this.snapshot = snapshot; }
    }
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    private static final class Operation {
        final Managed owner;
        final CompletableFuture<PaperSecuritySession> result = new CompletableFuture<>();
        volatile ScheduledFuture<?> timeout;
    }
    private record Evaluation(PaperSecurityDecision decision, Throwable failure) {}
}
