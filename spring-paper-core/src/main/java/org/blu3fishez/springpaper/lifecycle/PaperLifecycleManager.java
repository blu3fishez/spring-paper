package org.blu3fishez.springpaper.lifecycle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.blu3fishez.springpaper.annotation.PaperStart;
import org.blu3fishez.springpaper.annotation.PaperStop;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

/** Explicit hooks run on the Paper thread, including cleanup of a partially failed start. */
public final class PaperLifecycleManager implements AutoCloseable {
    private final List<Hook> pending = new ArrayList<>();
    private final List<Hook> started = new ArrayList<>();
    private boolean closed;

    public PaperLifecycleManager(ApplicationContext context, Map<String, Class<?>> candidates) {
        candidates.forEach((name, type) -> {
            Method start = null;
            Method stop = null;
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (method.isAnnotationPresent(PaperStart.class)) {
                    if (start != null) throw new IllegalArgumentException(name + ": only one PaperStart is allowed");
                    start = validate(method);
                }
                if (method.isAnnotationPresent(PaperStop.class)) {
                    if (stop != null) throw new IllegalArgumentException(name + ": only one PaperStop is allowed");
                    stop = validate(method);
                }
            }
            if (start != null || stop != null) {
                Object bean = context.getBean(name);
                int phase = start == null ? 0 : start.getAnnotation(PaperStart.class).phase();
                pending.add(new Hook(name, bean, invocable(start, bean), invocable(stop, bean), phase));
            }
        });
        pending.sort(Comparator.comparingInt(Hook::phase).thenComparing(Hook::name));
    }
    private static Method invocable(Method method, Object bean) {
        return method == null ? null : AopUtils.selectInvocableMethod(method, bean.getClass());
    }
    private static Method validate(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0 || method.getReturnType() != void.class) {
            throw new IllegalArgumentException(method + ": lifecycle hook must be public void with no arguments");
        }
        return method;
    }
    public void start() {
        for (Hook hook : pending) {
            started.add(hook);
            invoke(hook, hook.start);
        }
        pending.clear();
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Hook hook : started.reversed()) {
            try { invoke(hook, hook.stop); }
            catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        }
        started.clear();
        if (failure != null) throw failure;
    }
    private static void invoke(Hook hook, Method method) {
        if (method == null) return;
        try { method.invoke(hook.bean); }
        catch (InvocationTargetException error) { throw new IllegalStateException("Lifecycle hook failed: " + hook.name, error.getCause()); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Lifecycle hook is not invocable: " + hook.name, error); }
    }
    private record Hook(String name, Object bean, Method start, Method stop, int phase) {}
}
