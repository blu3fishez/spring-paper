package org.blu3fishez.springpaper.command;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.service.PaperInvocationService;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

/** Binds descriptor commands to managed beans; aliases/permissions remain owned by plugin.yml. */
@RequiredArgsConstructor
public final class PaperCommandRegistrar implements AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperInvocationService invocations;
    private final List<Binding> bindings = new ArrayList<>();
    private final Set<String> labels = new HashSet<>();
    private volatile boolean closed;
    private final UnaryOperator<String> resolve;

    public PaperCommandRegistrar(JavaPlugin plugin, PaperInvocationService invocations) {
        this(plugin, invocations, UnaryOperator.identity());
    }

    public void register(String beanName, Object bean, PaperCommandExecutor metadata) {
        if (closed) throw new IllegalStateException("Paper command registrar is closed");
        if (!(bean instanceof CommandExecutor executor)) throw new IllegalArgumentException(beanName + " must implement CommandExecutor");
        if (metadata.commands().length == 0) throw new IllegalArgumentException(beanName + ": commands must not be empty");
        for (String declared : metadata.commands()) {
            String label = resolve.apply(declared);
            if (!labels.add(label)) throw new IllegalArgumentException("Duplicate command '" + label + "' at " + beanName);
            PluginCommand command = plugin.getCommand(label);
            if (command == null || !command.getName().equals(label)) {
                throw new IllegalArgumentException(beanName + ": canonical command '" + label + "' is missing from plugin.yml");
            }
            CommandExecutor wrapper = (sender, input, alias, args) -> {
                if (closed) return true;
                try {
                    return (Boolean) invocations.invoke(new PaperInvocation(sender, input, false, PaperInvocation.Kind.COMMAND),
                            () -> executor.onCommand(sender, input, alias, args));
                } catch (Exception error) { throw new org.bukkit.command.CommandException("Spring Paper command " + label + " failed", error); }
            };
            TabCompleter completer = bean instanceof TabCompleter tabs ? (sender, input, alias, args) -> {
                if (closed) return List.of();
                try {
                    @SuppressWarnings("unchecked")
                    List<String> result = (List<String>) invocations.invoke(
                            new PaperInvocation(sender, input, false, PaperInvocation.Kind.COMPLETION),
                            () -> tabs.onTabComplete(sender, input, alias, args));
                    return result;
                } catch (Exception error) { throw new org.bukkit.command.CommandException("Spring Paper completion " + label + " failed", error); }
            } : null;
            bindings.add(new Binding(command, command.getExecutor(), command.getTabCompleter(), wrapper, completer));
            command.setExecutor(wrapper);
            command.setTabCompleter(completer);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Binding binding : bindings.reversed()) {
            if (binding.command.getExecutor() == binding.executor) binding.command.setExecutor(binding.previousExecutor);
            if (binding.command.getTabCompleter() == binding.completer) binding.command.setTabCompleter(binding.previousCompleter);
        }
        bindings.clear();
    }
    private record Binding(PluginCommand command, CommandExecutor previousExecutor,
                           TabCompleter previousCompleter, CommandExecutor executor, TabCompleter completer) {}
}
