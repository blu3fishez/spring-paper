package org.blu3fishez.springpaper.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.service.PaperInvocationService;
import org.bukkit.command.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class CommandRegistrationTest {
    @PaperCommandExecutor(commands = "guide")
    public static class Guide implements CommandExecutor, TabCompleter {
        final AtomicInteger calls = new AtomicInteger();
        @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) { calls.incrementAndGet(); return true; }
        @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) { return List.of("next"); }
    }
    private PluginCommand command(JavaPlugin owner) throws Exception {
        var constructor = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
        constructor.setAccessible(true);
        return constructor.newInstance("guide", owner);
    }
    @Test void invokesManagedExecutorAndCompleterThenRestoresPriorOwners() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        PluginCommand command = command(plugin);
        CommandExecutor previous = (sender, input, label, args) -> false;
        command.setExecutor(previous);
        when(plugin.getCommand("guide")).thenReturn(command);
        Guide guide = new Guide();
        var registrar = new PaperCommandRegistrar(plugin, new PaperInvocationService(List.of()));
        registrar.register("guide", guide, Guide.class.getAnnotation(PaperCommandExecutor.class));
        var connected = command.getExecutor();
        assertTrue(connected.onCommand(mock(CommandSender.class), command, "guide", new String[0]));
        assertEquals(1, guide.calls.get());
        assertEquals(List.of("next"), command.getTabCompleter().onTabComplete(mock(CommandSender.class), command, "guide", new String[0]));
        assertThrows(IllegalArgumentException.class, () -> registrar.register("duplicate", guide, Guide.class.getAnnotation(PaperCommandExecutor.class)));
        registrar.close(); registrar.close();
        assertSame(previous, command.getExecutor());
        connected.onCommand(mock(CommandSender.class), command, "guide", new String[0]);
        assertEquals(1, guide.calls.get(), "stale executor must reject new input");
    }
    @Test void doesNotOverwriteASubsequentOwnersRegistration() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        PluginCommand command = command(plugin);
        when(plugin.getCommand("guide")).thenReturn(command);
        var registrar = new PaperCommandRegistrar(plugin, new PaperInvocationService(List.of()));
        registrar.register("guide", new Guide(), Guide.class.getAnnotation(PaperCommandExecutor.class));
        CommandExecutor other = (sender, input, label, args) -> false;
        command.setExecutor(other);
        registrar.close();
        assertSame(other, command.getExecutor());
    }
}
