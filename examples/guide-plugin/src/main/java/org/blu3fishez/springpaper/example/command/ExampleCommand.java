package org.blu3fishez.springpaper.example.command;

import java.nio.file.Files;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.example.listener.ExampleListener;
import org.blu3fishez.springpaper.example.listener.ExampleAfterListener;
import org.blu3fishez.springpaper.example.service.ExamplePdcVerificationService;
import org.blu3fishez.springpaper.example.service.NoteService;
import org.blu3fishez.springpaper.example.service.OperatorService;
import org.blu3fishez.springpaper.example.security.service.ExampleConnectionVerificationService;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.command.*;
import org.bukkit.plugin.java.JavaPlugin;

@PaperCommandExecutor(commands = "springpaper", aliases = "sp", description = "Verify Spring Paper integrations",
        permission = "springpaper.verify", usage = "/springpaper")
@RequiredArgsConstructor
public final class ExampleCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final PaperTasks tasks;
    private final NoteService notes;
    private final OperatorService operators;
    private final ExampleListener listener;
    private final ExampleConnectionVerificationService connectionSecurity;
    private final ExampleAfterListener after;
    private final ExamplePdcVerificationService pdc;
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String authorized = operators.verifyConsole();
        String persistentData = pdc.verify();
        tasks.async(() -> {
            notes.ensureNote(1, "committed-value");
            String first = notes.read(1);
            int loadsAfterFirst = notes.reads();
            String second = notes.read(1);
            // 캐시 TTL 만료 뒤의 정상 재조회도 허용한다. 누적 횟수 대신 연속 두 번째 조회의 적중을 검사한다.
            if (!first.equals("committed-value") || !first.equals(second) || notes.reads() != loadsAfterFirst) {
                throw new IllegalStateException("JPA/cache verification failed");
            }
            return authorized + "\n" + connectionSecurity.verify().toCompletableFuture().join() + "\n" + persistentData;
        }).whenComplete((result, error) -> tasks.main(() -> {
            if (error != null) throw new IllegalStateException("Spring Paper smoke failed", error);
            if (listener.signals() != 1 || after.getSignals() != 1) throw new IllegalStateException("Paper event verification failed");
            try {
                Files.createDirectories(plugin.getDataFolder().toPath());
                Files.writeString(plugin.getDataFolder().toPath().resolve("smoke-success.txt"),
                        "paper=" + plugin.getServer().getMinecraftVersion() + "\n" + result + "\njpa=committed\ncache=hit\nevent=1\nevent-order=relation\n");
            } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            sender.sendPlainMessage("SPRING_PAPER_SMOKE_OK");
        }).exceptionally(failure -> {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "SPRING_PAPER_SMOKE_FAILED", failure);
            return null;
        }));
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return List.of(); }
}
