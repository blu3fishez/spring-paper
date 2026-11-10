package org.blu3fishez.springpaper.examples.business.inbox;

import lombok.RequiredArgsConstructor;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.bukkit.command.*;
import org.springframework.context.annotation.Conditional;

@PaperCommandExecutor(commands = "paper-inbox", description = "Show pending mail")
@Conditional(InboxEnabled.class) // A direct component scan must also respect this feature's setting.
@RequiredArgsConstructor
public final class InboxCommand implements CommandExecutor {
    private final InboxService inbox;
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        inbox.show(sender); return true;
    }
}
