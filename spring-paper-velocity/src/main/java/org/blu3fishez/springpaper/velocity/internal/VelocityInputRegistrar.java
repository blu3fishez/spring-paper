package org.blu3fishez.springpaper.velocity.internal;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.blu3fishez.springpaper.velocity.annotation.VelocityCommand;
import org.blu3fishez.springpaper.velocity.annotation.VelocityEventListener;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Owns only the registrations acquired by this application, including partial native failures. */
public final class VelocityInputRegistrar implements AutoCloseable {
    private final Object plugin;
    private final ProxyServer proxy;
    private final BooleanSupplier stopping;
    private final List<Object> listeners = new ArrayList<>();
    private final List<CommandMeta> commands = new ArrayList<>();
    private boolean closed;

    public VelocityInputRegistrar(Object plugin, ProxyServer proxy, BooleanSupplier stopping) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.stopping = stopping;
    }

    public static void validateDefinitions(ConfigurableListableBeanFactory beans) {
        for (String name : beans.getBeanDefinitionNames()) {
            if (!listener(beans, name) && command(beans, name) == null) continue;
            var definition = beans.getBeanDefinition(name);
            if (!definition.isSingleton() || definition.isLazyInit()) {
                throw new IllegalArgumentException(name + ": Velocity input beans must be eager singletons; exclude inactive inputs in configuration");
            }
        }
    }

    public synchronized void register(AnnotationConfigApplicationContext context) {
        var beans = context.getBeanFactory();
        var candidates = java.util.stream.Stream.concat(Arrays.stream(beans.getBeanDefinitionNames()),
                        Arrays.stream(beans.getSingletonNames())).distinct().sorted()
                .filter(name -> listener(beans, name) || command(beans, name) != null).toList();
        var registrations = new ArrayList<Registration>();
        Set<String> aliases = new HashSet<>();
        for (String name : candidates) {
            Object bean = context.getBean(name);
            boolean listener = listener(beans, name);
            var annotation = command(beans, name);
            if (listener && AopUtils.isJdkDynamicProxy(bean)) {
                throw new IllegalArgumentException(name + ": Native @Subscribe listeners require a concrete bean or class proxy; JDK proxies hide the subscribed methods");
            }
            CommandMeta meta = null;
            if (annotation != null) {
                if (!(bean instanceof Command)) throw new IllegalArgumentException(name + ": @VelocityCommand requires a native Command bean");
                List<String> labels = new ArrayList<>();
                labels.add(context.getEnvironment().resolveRequiredPlaceholders(annotation.value()));
                for (String alias : annotation.aliases()) labels.add(context.getEnvironment().resolveRequiredPlaceholders(alias));
                for (String alias : labels) {
                    if (alias.isBlank() || alias.chars().anyMatch(Character::isWhitespace) || alias.startsWith("/"))
                        throw new IllegalArgumentException(name + ": Invalid Velocity command alias: " + alias);
                    String normalized = alias.toLowerCase(Locale.ROOT);
                    if (!aliases.add(normalized) || proxy.getCommandManager().getCommandMeta(normalized) != null)
                        throw new IllegalArgumentException(name + ": Velocity command alias already registered: " + alias);
                }
                meta = proxy.getCommandManager().metaBuilder(labels.getFirst())
                        .aliases(labels.subList(1, labels.size()).toArray(String[]::new)).plugin(plugin).build();
            }
            registrations.add(new Registration(bean, listener, meta));
        }
        for (var registration : registrations) {
            ensureOpen();
            if (registration.listener) {
                // Record first: native registration may mutate its maps before throwing.
                listeners.add(registration.bean);
                proxy.getEventManager().register(plugin, registration.bean);
            }
            ensureOpen();
            if (registration.command != null) {
                commands.add(registration.command);
                proxy.getCommandManager().register(registration.command, (Command) registration.bean);
            }
        }
        ensureOpen();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        Throwable failure = null;
        for (int i = commands.size() - 1; i >= 0; i--) {
            try { proxy.getCommandManager().unregister(commands.get(i)); }
            catch (RuntimeException | Error error) { failure = collect(failure, error); }
        }
        for (int i = listeners.size() - 1; i >= 0; i--) {
            try { proxy.getEventManager().unregisterListener(plugin, listeners.get(i)); }
            catch (RuntimeException | Error error) { failure = collect(failure, error); }
        }
        commands.clear();
        listeners.clear();
        if (failure instanceof Error fatal) throw fatal;
        if (failure instanceof RuntimeException runtime) throw runtime;
    }

    private void ensureOpen() {
        if (closed || stopping.getAsBoolean()) throw new IllegalStateException("Velocity application is stopping");
    }
    private static boolean listener(ConfigurableListableBeanFactory beans, String name) {
        return beans.findAnnotationOnBean(name, VelocityEventListener.class, false) != null;
    }
    private static VelocityCommand command(ConfigurableListableBeanFactory beans, String name) {
        return beans.findAnnotationOnBean(name, VelocityCommand.class, false);
    }
    private static Throwable collect(Throwable failure, Throwable error) {
        if (failure == null) return error;
        if (failure != error) failure.addSuppressed(error);
        return failure;
    }
    private record Registration(Object bean, boolean listener, CommandMeta command) {}
}
