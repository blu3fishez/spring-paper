package org.blu3fishez.springpaper.listener;

import lombok.RequiredArgsConstructor;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.blu3fishez.springpaper.service.PaperInvocationService;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ReflectionUtils;

/** Owns only its listener tokens, preserving Paper's priority, cancellation and calling thread. */
@RequiredArgsConstructor
public final class PaperListenerRegistrar implements AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperInvocationService invocations;
    private final List<Listener> registrations = new ArrayList<>();
    private volatile boolean closed;

    /** Standalone registration. Use one batch for relationships between several listeners. */
    public void register(String name, Object bean, Class<?> type) {
        registerAll(List.of(new Candidate(name, bean, type)));
    }

    /** Register the active role's listeners together; no Order annotation is required. */
    public void registerAll(List<Candidate> candidates) {
        registerAll(candidates, List.of());
    }

    /** Batch plus composition-root relationships; all constraints share one validated graph. */
    public void registerAll(List<Candidate> candidates, List<PaperEventRelation> relations) {
        if (closed) throw new IllegalStateException("Paper listener registrar is closed");
        var plan = PaperEventPlan.build(List.copyOf(candidates), List.copyOf(relations));
        Map<Candidate, Listener> tokens = new IdentityHashMap<>();
        for (var entry : plan) {
            String name = entry.candidate().name();
            Object bean = entry.candidate().bean();
            Method method = entry.method();
            EventHandler handler = entry.metadata();
            Listener token = tokens.computeIfAbsent(entry.candidate(), candidate -> {
                Listener created = new Listener() {}; registrations.add(created); return created;
            });
            Method invocable;
            // 원본 target을 꺼내 호출하면 Security/Transactional 등 Spring advice를 우회한다.
            // EventListenerMethodProcessor와 같은 selectInvocableMethod 계약으로 프록시에 노출된 메서드만 호출한다.
            try { invocable = AopUtils.selectInvocableMethod(method, bean.getClass()); }
            catch (IllegalStateException error) {
                throw new IllegalArgumentException(name + ": event method is hidden by a JDK proxy; move advice to a service or expose an invocable interface", error);
            }
            // 소비자 @Bean은 package-private 입력 어댑터도 반환할 수 있다. public 메서드라도 선언 클래스가
            // 비공개이면 다른 패키지의 invoke가 실패한다. Spring ApplicationListenerMethodAdapter와 같이
            // 프록시에서 선택한 메서드의 접근성만 보완하며 원본 target으로 advice를 우회하지 않는다.
            ReflectionUtils.makeAccessible(invocable);
            Class<? extends Event> eventType = entry.eventType();
            plugin.getServer().getPluginManager().registerEvent(eventType, token, handler.priority(),
                    (listener, event) -> {
                        if (closed || !eventType.isInstance(event)) return;
                        try {
                            invocations.invoke(new PaperInvocation(caller(event), event, event.isAsynchronous(), PaperInvocation.Kind.EVENT),
                                    () -> invoke(invocable, bean, event));
                        } catch (Exception error) { throw new EventException(error); }
                    }, plugin, handler.ignoreCancelled());
        }
    }

    public record Candidate(String name, Object bean, Class<?> type) {
        public Candidate { java.util.Objects.requireNonNull(name); java.util.Objects.requireNonNull(bean); java.util.Objects.requireNonNull(type); }
    }

    private static Object invoke(Method method, Object bean, Event event) throws Exception {
        try { return method.invoke(bean, event); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw error;
        }
    }
    private static CommandSender caller(Event event) {
        if (event instanceof PlayerEvent player) return player.getPlayer();
        if (event instanceof EntityEvent entity && entity.getEntity() instanceof Player player) return player;
        return null;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        registrations.forEach(HandlerList::unregisterAll);
        registrations.clear();
    }
}
