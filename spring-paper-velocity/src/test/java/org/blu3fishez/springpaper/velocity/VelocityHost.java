package org.blu3fishez.springpaper.velocity;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.mojang.brigadier.tree.CommandNode;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Continuation;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.velocity.bootstrap.SpringVelocityApplication;
import org.slf4j.Logger;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/** API host fixture. Native calls retain exact owner/listener/metadata identities for assertions. */
final class VelocityHost {
    final PluginOwner plugin = new PluginOwner();
    final ProxyServer proxy = mock(ProxyServer.class);
    final Logger logger = mock(Logger.class);
    final EventManager events = mock(EventManager.class);
    final CommandManager commands = mock(CommandManager.class);
    final Path directory = Path.of("build", "velocity-test");
    final List<Object> listeners = new ArrayList<>();
    final Map<String, CommandMeta> commandMetas = new LinkedHashMap<>();
    final List<String> effects = new ArrayList<>();

    VelocityHost() {
        when(proxy.getEventManager()).thenReturn(events);
        when(proxy.getCommandManager()).thenReturn(commands);
        when(commands.metaBuilder(anyString())).thenAnswer(call -> new MetaBuilder(call.getArgument(0)));
        when(commands.getCommandMeta(anyString())).thenAnswer(call -> commandMetas.get(call.getArgument(0)));
        doAnswer(call -> {
            Object listener = call.getArgument(1);
            listeners.add(listener);
            effects.add("listener.register");
            return null;
        }).when(events).register(same(plugin), any());
        doAnswer(call -> {
            listeners.remove(call.getArgument(1));
            effects.add("listener.unregister");
            return null;
        }).when(events).unregisterListener(same(plugin), any());
        doAnswer(call -> {
            CommandMeta meta = call.getArgument(0);
            meta.getAliases().forEach(alias -> commandMetas.put(alias, meta));
            effects.add("command.register");
            return null;
        }).when(commands).register(any(CommandMeta.class), any());
        doAnswer(call -> {
            CommandMeta meta = call.getArgument(0);
            meta.getAliases().forEach(alias -> commandMetas.remove(alias, meta));
            effects.add("command.unregister");
            return null;
        }).when(commands).unregister(any(CommandMeta.class));
    }

    @SafeVarargs final SpringVelocityApplication application(Class<?> configuration,
            ApplicationContextInitializer<ConfigurableApplicationContext>... initializers) {
        return new SpringVelocityApplication(plugin, proxy, logger, directory, configuration, initializers);
    }

    static void execute(EventTask task) {
        if (!task.requiresAsync()) throw new AssertionError("Lifecycle work must request a native asynchronous task");
        var resumes = new AtomicInteger();
        task.execute(new Continuation() {
            @Override public void resume() { resumes.incrementAndGet(); }
            @Override public void resumeWithException(Throwable error) { throw new AssertionError(error); }
        });
        if (resumes.get() != 1) throw new AssertionError("Native continuation must resume exactly once");
    }

    static final class PluginOwner implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();
        @Override public void close() { closes.incrementAndGet(); }
    }

    private static final class MetaBuilder implements CommandMeta.Builder {
        final List<String> aliases = new ArrayList<>();
        final List<CommandNode<CommandSource>> hints = new ArrayList<>();
        Object plugin;
        MetaBuilder(String alias) { aliases.add(alias.toLowerCase(Locale.ROOT)); }
        @Override public CommandMeta.Builder aliases(String... values) {
            for (String value : values) aliases.add(value.toLowerCase(Locale.ROOT));
            return this;
        }
        @Override public CommandMeta.Builder hint(CommandNode<CommandSource> hint) { hints.add(hint); return this; }
        @Override public CommandMeta.Builder plugin(Object value) { plugin = value; return this; }
        @Override public CommandMeta build() {
            List<String> capturedAliases = List.copyOf(aliases);
            List<CommandNode<CommandSource>> capturedHints = List.copyOf(hints);
            Object capturedPlugin = plugin;
            return new CommandMeta() {
                @Override public Collection<String> getAliases() { return capturedAliases; }
                @Override public Collection<CommandNode<CommandSource>> getHints() { return capturedHints; }
                @Override public Object getPlugin() { return capturedPlugin; }
            };
        }
    }
}
