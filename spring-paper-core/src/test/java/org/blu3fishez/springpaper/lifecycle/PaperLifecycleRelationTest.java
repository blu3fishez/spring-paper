package org.blu3fishez.springpaper.lifecycle;

import static org.junit.jupiter.api.Assertions.*;
import static org.blu3fishez.springpaper.lifecycle.PaperLifecycleRelation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class PaperLifecycleRelationTest {
    public interface Operations { void start(); void stop(); }
    public static class Owner implements Operations {
        final String name;
        final List<String> calls;
        boolean failStart;
        boolean failStop;
        Owner(String name, List<String> calls) { this.name = name; this.calls = calls; }
        @Override @OnPaperMainThreadStartup public void start() {
            calls.add("start:" + name);
            if (failStart) throw new IllegalStateException("broken start:" + name);
        }
        @Override @OnPaperMainThreadShutdown public void stop() {
            calls.add("stop:" + name);
            if (failStop) throw new IllegalStateException("broken stop:" + name);
        }
    }
    public static class StopOnly {
        final List<String> calls;
        StopOnly(List<String> calls) { this.calls = calls; }
        @OnPaperMainThreadShutdown public void close() { calls.add("stop:only"); }
    }
    @Configuration(proxyBeanMethods = false)
    public static class NamedRelations {
        @Bean PaperLifecycleRelation startup(@Qualifier("journalAlias") Operations journal,
                @Qualifier("aProducer") Operations producer) { return startupBefore(journal, producer); }
        @Bean PaperLifecycleRelation shutdown(@Qualifier("journalAlias") Operations journal,
                @Qualifier("aProducer") Operations producer) { return shutdownBefore(journal, producer); }
    }

    @Test void plainHooksNeedNoOrderingAndStopInReverseActivationOrder() {
        try (var f = new Fixture()) {
            f.owner("z"); f.owner("a"); f.refresh();
            try (var lifecycle = f.manager()) {
                lifecycle.start();
                assertEquals(List.of("start:a", "start:z"), f.calls);
            }
            assertEquals(List.of("start:a", "start:z", "stop:z", "stop:a"), f.calls);
        }
    }

    @Test void standardSpringQualifiersAndAliasesSelectTwoSameTypeOwnersWithIndependentOrders() {
        try (var f = new Fixture()) {
            f.owner("aProducer"); f.owner("zJournal");
            f.context.getBeanFactory().registerAlias("zJournal", "journalAlias");
            f.context.register(NamedRelations.class);
            f.refresh();
            try (var lifecycle = f.manager()) { lifecycle.start(); }
            assertEquals(List.of("start:zJournal", "start:aProducer", "stop:zJournal", "stop:aProducer"), f.calls);
        }
    }

    @Test void startupCycleIsRejectedBeforeAnyPaperMutationAndNamesTheOwners() {
        try (var f = new Fixture()) {
            var a = f.owner("a"); var b = f.owner("b");
            f.relation("ab", startupBefore(a, b)); f.relation("ba", startupBefore(b, a)); f.refresh();
            var error = assertThrows(IllegalArgumentException.class, f::manager);
            assertTrue(error.getMessage().contains("start"));
            assertTrue(error.getMessage().contains("a"));
            assertTrue(error.getMessage().contains("b"));
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void shutdownCycleIsAlsoRejectedBeforeAnyStart() {
        try (var f = new Fixture()) {
            var a = f.owner("a"); var b = f.owner("b");
            f.relation("ab", shutdownBefore(a, b)); f.relation("ba", shutdownBefore(b, a)); f.refresh();
            assertThrows(IllegalArgumentException.class, f::manager);
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void failedStartCleansAttemptedOwnersAndPreservesStopPathsThroughUnattemptedOwners() {
        try (var f = new Fixture()) {
            var a = f.owner("a"); var b = f.owner("b"); var c = f.owner("c"); b.failStart = true;
            f.relation("start-ab", startupBefore(a, b)); f.relation("start-bc", startupBefore(b, c));
            f.relation("stop-ac", shutdownBefore(a, c)); f.relation("stop-cb", shutdownBefore(c, b)); f.refresh();
            var lifecycle = f.manager();
            assertThrows(IllegalStateException.class, lifecycle::start);
            lifecycle.close(); lifecycle.close();
            assertEquals(List.of("start:a", "start:b", "stop:a", "stop:b"), f.calls);
        }
    }

    @Test void stopOnlyOwnerNeedsNoEmptyStartAndCanPrecedeAnotherOwnersStop() {
        try (var f = new Fixture()) {
            var owner = f.owner("a");
            var only = new StopOnly(f.calls);
            f.bean("z", only, StopOnly.class);
            f.relation("stop", shutdownBefore(only, owner)); f.refresh();
            try (var lifecycle = f.manager()) { lifecycle.start(); }
            assertEquals(List.of("start:a", "stop:only", "stop:a"), f.calls);
        }
    }

    @Test void relationToAMissingHookFailsRatherThanCallingAnUnmarkedMethod() {
        try (var f = new Fixture()) {
            var owner = f.owner("a"); var only = new StopOnly(f.calls);
            f.bean("only", only, StopOnly.class);
            f.relation("invalid-start", startupBefore(only, owner)); f.refresh();
            var error = assertThrows(IllegalArgumentException.class, f::manager);
            assertTrue(error.getMessage().contains("only"));
            assertTrue(error.getMessage().contains("start"));
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void aDetachedObjectCannotMasqueradeAsASpringLifecycleOwner() {
        try (var f = new Fixture()) {
            var owner = f.owner("a");
            f.relation("detached", startupBefore(owner, new Owner("not-a-bean", f.calls))); f.refresh();
            var error = assertThrows(IllegalArgumentException.class, f::manager);
            assertTrue(error.getMessage().contains("detached"));
            assertTrue(f.calls.isEmpty());
        }
    }

    @Test void cleanupAttemptsEveryOwnerAndAggregatesFailures() {
        try (var f = new Fixture()) {
            var a = f.owner("a"); var b = f.owner("b"); a.failStop = true; b.failStop = true; f.refresh();
            var lifecycle = f.manager(); lifecycle.start();
            var error = assertThrows(IllegalStateException.class, lifecycle::close);
            assertEquals(1, error.getSuppressed().length);
            assertEquals(List.of("start:a", "start:b", "stop:b", "stop:a"), f.calls);
            lifecycle.close();
        }
    }

    @Test void proxyInvocationUsesTheSelectedSpringObjectRatherThanBypassingItsAdvice() {
        try (var f = new Fixture()) {
            var target = new Owner("target", f.calls);
            var proxyFactory = new org.springframework.aop.framework.ProxyFactory(target);
            proxyFactory.setInterfaces(Operations.class);
            proxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) call -> {
                f.calls.add("advice:" + call.getMethod().getName());
                return call.proceed();
            });
            Object proxy = proxyFactory.getProxy();
            f.bean("a", proxy, Object.class); f.candidates.put("a", Owner.class);
            var b = f.owner("b"); f.relation("start", startupBefore(b, proxy)); f.refresh();
            try (var lifecycle = f.manager()) { lifecycle.start(); }
            assertEquals(List.of("start:b", "advice:start", "start:target", "advice:stop", "stop:target", "stop:b"), f.calls);
        }
    }

    @Test void registeringTheSamePhysicalOwnerTwiceCannotDoubleActivateIt() {
        try (var f = new Fixture()) {
            var owner = f.owner("a"); f.bean("b", owner, Owner.class); f.refresh();
            assertThrows(IllegalArgumentException.class, f::manager);
            assertTrue(f.calls.isEmpty());
        }
    }

    @SuppressWarnings("deprecation")
    public static class AmbiguousAnnotations {
        @org.blu3fishez.springpaper.annotation.PaperStart
        @OnPaperMainThreadStartup public void start() { fail("must reject competing markers first"); }
    }
    @Test void migrationCannotSilentlyCombineLegacyAndExplicitStartupMarkers() {
        try (var f = new Fixture()) {
            f.bean("ambiguous", new AmbiguousAnnotations(), AmbiguousAnnotations.class); f.refresh();
            var error = assertThrows(IllegalArgumentException.class, f::manager);
            assertTrue(error.getMessage().contains("one main-thread lifecycle annotation"));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        final Map<String, Class<?>> candidates = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        Owner owner(String name) {
            var owner = new Owner(name, calls); bean(name, owner, Owner.class); return owner;
        }
        <T> void bean(String name, T instance, Class<T> type) {
            context.registerBean(name, type, () -> instance); candidates.put(name, type);
        }
        void relation(String name, PaperLifecycleRelation relation) {
            context.registerBean(name, PaperLifecycleRelation.class, () -> relation);
        }
        void refresh() { context.refresh(); }
        PaperLifecycleManager manager() { return new PaperLifecycleManager(context, candidates); }
        @Override public void close() { context.close(); }
    }
}
