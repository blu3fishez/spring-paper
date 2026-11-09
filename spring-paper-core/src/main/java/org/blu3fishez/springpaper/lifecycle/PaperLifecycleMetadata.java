package org.blu3fishez.springpaper.lifecycle;

import org.blu3fishez.springpaper.internal.PaperLifecycleAnnotations;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.blu3fishez.springpaper.annotation.PaperStart;
import org.blu3fishez.springpaper.annotation.PaperStop;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

/** Effective Java methods and Spring proxy invocation, independent of ordering and execution. */
@SuppressWarnings("deprecation")
final class PaperLifecycleMetadata {
    private PaperLifecycleMetadata() {}

    static List<PaperLifecycleHook> read(ApplicationContext context, Map<String, Class<?>> candidates) {
        List<PaperLifecycleHook> hooks = new ArrayList<>();
        candidates.forEach((name, type) -> {
            Method start = null;
            Method stop = null;
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (PaperLifecycleAnnotations.hook(method)) {
                    PaperLifecycleAnnotations.validateExclusive(method);
                    validate(method);
                }
            }
            // getMethods selects effective public overrides; removed annotations must not revive a parent hook.
            for (Method method : type.getMethods()) {
                if (method.isBridge() || method.isSynthetic()) continue;
                if (PaperLifecycleAnnotations.startup(method)) {
                    if (start != null) throw new IllegalArgumentException(name + ": only one Paper main-thread startup is allowed");
                    start = validate(method);
                }
                if (PaperLifecycleAnnotations.shutdown(method)) {
                    if (stop != null) throw new IllegalArgumentException(name + ": only one Paper main-thread shutdown is allowed");
                    stop = validate(method);
                }
            }
            if (start == null && stop == null) return;
            Object bean = context.getBean(name);
            int phase = start == null || start.getAnnotation(PaperStart.class) == null ? 0 : phase(context, start.getAnnotation(PaperStart.class).phase(),
                    start.getAnnotation(PaperStart.class).phaseString(), 0);
            int stopPhase = stop == null || stop.getAnnotation(PaperStop.class) == null ? Integer.MIN_VALUE : phase(context, stop.getAnnotation(PaperStop.class).phase(),
                    stop.getAnnotation(PaperStop.class).phaseString(), Integer.MIN_VALUE);
            hooks.add(new PaperLifecycleHook(name, bean, invocable(start, bean), invocable(stop, bean), phase,
                    stopPhase == Integer.MIN_VALUE ? phase : stopPhase));
        });
        return List.copyOf(hooks);
    }

    private static int phase(ApplicationContext context, int declared, String expression, int defaultPhase) {
        if (expression.isBlank()) return declared;
        if (declared != defaultPhase) throw new IllegalArgumentException("Use phase or phaseString, not both");
        String resolved = context.getEnvironment().resolveRequiredPlaceholders(expression);
        try { return Integer.parseInt(resolved); }
        catch (NumberFormatException failure) {
            throw new IllegalArgumentException("Lifecycle phase must resolve to an integer: " + expression, failure);
        }
    }

    private static Method invocable(Method method, Object bean) {
        if (method == null) return null;
        Method selected = AopUtils.selectInvocableMethod(method, bean.getClass());
        ReflectionUtils.makeAccessible(selected);
        return selected;
    }

    private static Method validate(Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0 || method.getReturnType() != void.class) {
            throw new IllegalArgumentException(method + ": lifecycle hook must be public void with no arguments");
        }
        return method;
    }
}
