package org.blu3fishez.springpaper.listener;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import org.blu3fishez.springpaper.annotation.PaperEventOrder;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.springframework.util.ReflectionUtils;

/** Validates the complete batch before Paper side effects. Default order stays deterministic. */
final class PaperEventPlan {
    private PaperEventPlan() {}

    @SuppressWarnings("unchecked")
    static List<Handler> build(List<PaperListenerRegistrar.Candidate> candidates, List<PaperEventRelation> relations) {
        List<Handler> handlers = new ArrayList<>();
        for (var candidate : candidates) {
            Class<?> type = candidate.type();
            if (!(candidate.bean() instanceof Listener)) throw new IllegalArgumentException(candidate.name() + " must implement Bukkit Listener");
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (method.isBridge() || method.isSynthetic()) continue;
                EventHandler event = method.getAnnotation(EventHandler.class);
                if (method.isAnnotationPresent(PaperEventOrder.class) && event == null) {
                    throw new IllegalArgumentException(candidate.name() + ": PaperEventOrder requires EventHandler");
                }
                if (event == null) continue;
                if (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                        || method.getParameterCount() != 1 || method.getReturnType() != void.class
                        || !Event.class.isAssignableFrom(method.getParameterTypes()[0])) {
                    throw new IllegalArgumentException(candidate.name() + ": @EventHandler requires public void method(Event)");
                }
            }
            // Java override semantics choose one effective public handler; reflection enumeration order is not stable.
            Arrays.stream(type.getMethods()).filter(method -> !method.isBridge() && !method.isSynthetic())
                    .sorted(Comparator.comparing(Method::toGenericString)).forEach(method -> {
                        EventHandler event = method.getAnnotation(EventHandler.class);
                        if (event == null) return;
                        Class<? extends Event> eventType = (Class<? extends Event>) method.getParameterTypes()[0];
                        handlers.add(new Handler(candidate, method, event, eventType, nativeHandlers(eventType), handlers.size()));
                    });
        }
        return order(handlers, relations);
    }

    private static HandlerList nativeHandlers(Class<? extends Event> event) {
        try {
            Method accessor = event.getMethod("getHandlerList");
            if (!Modifier.isStatic(accessor.getModifiers()) || accessor.getReturnType() != HandlerList.class) {
                throw new IllegalArgumentException("Invalid native HandlerList on " + event.getName());
            }
            ReflectionUtils.makeAccessible(accessor);
            HandlerList handlers = (HandlerList) accessor.invoke(null);
            if (handlers == null) throw new IllegalArgumentException("Null native HandlerList on " + event.getName());
            return handlers;
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalArgumentException("Cannot resolve native handlers for " + event.getName(), failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Event needs a native static getHandlerList: " + event.getName(), failure);
        }
    }

    private static List<Handler> order(List<Handler> handlers, List<PaperEventRelation> relations) {
        // Index metadata, not consumer beans: hashCode/equals on a listener may depend on mutable game state.
        List<LinkedHashSet<Integer>> edges = new ArrayList<>();
        int[] remaining = new int[handlers.size()];
        for (Handler handler : handlers) edges.add(new LinkedHashSet<>());
        for (Handler source : handlers) {
            PaperEventOrder order = source.method.getAnnotation(PaperEventOrder.class);
            if (order == null) continue;
            for (Handler target : handlers) {
                // EntityDamageEvent and EntityDamageByEntityEvent, for example, share one HandlerList.
                // Grouping by Java event class would silently discard a real ordering relationship.
                if (source.handlers != target.handlers) continue;
                for (Class<?> before : order.before()) {
                    if (before.isAssignableFrom(target.candidate.type())) relation(source, target, edges, remaining);
                }
                for (Class<?> after : order.after()) {
                    if (after.isAssignableFrom(target.candidate.type())) relation(target, source, edges, remaining);
                }
            }
        }
        // 도메인 구현끼리 클래스 참조를 추가하지 않도록 최상위 구성도 같은 관계 그래프에 참여한다.
        // 별도 숫자 순서나 두 번째 dispatcher를 만들지 않고 동일한 cycle/native-priority 검증을 쓴다.
        for (PaperEventRelation rule : relations) {
            HandlerList nativeList = nativeHandlers(rule.event());
            var before = handlers.stream().filter(handler -> handler.handlers == nativeList
                    && handler.metadata.priority() == rule.priority()
                    && rule.before().isAssignableFrom(handler.candidate.type())).toList();
            var after = handlers.stream().filter(handler -> handler.handlers == nativeList
                    && handler.metadata.priority() == rule.priority()
                    && rule.after().isAssignableFrom(handler.candidate.type())).toList();
            for (Handler first : before) for (Handler last : after) relation(first, last, edges, remaining);
        }
        var ready = new PriorityQueue<Integer>();
        for (int i = 0; i < remaining.length; i++) if (remaining[i] == 0) ready.add(i);
        List<Handler> result = new ArrayList<>();
        while (!ready.isEmpty()) {
            int next = ready.remove(); result.add(handlers.get(next));
            for (int target : edges.get(next)) {
                if (--remaining[target] == 0) ready.add(target);
            }
        }
        if (result.size() != handlers.size()) {
            var unresolved = handlers.stream().filter(handler -> remaining[handler.position] > 0).map(Handler::description).toList();
            throw new IllegalArgumentException("Cyclic Paper event ordering: " + unresolved);
        }
        return List.copyOf(result);
    }

    private static void relation(Handler before, Handler after, List<LinkedHashSet<Integer>> edges, int[] remaining) {
        int nativePriority = Integer.compare(before.metadata.priority().getSlot(), after.metadata.priority().getSlot());
        if (nativePriority > 0) throw new IllegalArgumentException("Paper event order contradicts native priority: " + before.description() + " before " + after.description());
        if (nativePriority < 0) return; // Paper itself already guarantees this ordering.
        if (edges.get(before.position).add(after.position)) remaining[after.position]++;
    }

    record Handler(PaperListenerRegistrar.Candidate candidate, Method method, EventHandler metadata,
                   Class<? extends Event> eventType, HandlerList handlers, int position) {
        String description() { return candidate.name() + "#" + method.getName() + "(" + eventType.getSimpleName() + ")@" + metadata.priority(); }
    }
}
