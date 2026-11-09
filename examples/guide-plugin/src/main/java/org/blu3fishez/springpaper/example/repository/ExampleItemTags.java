package org.blu3fishez.springpaper.example.repository;

import java.util.UUID;
import org.blu3fishez.springpaper.pdc.*;
import org.bukkit.inventory.ItemStack;

@PaperPdcRepository
public interface ExampleItemTags extends PdcRepository<ItemStack, ExampleItemTags.State> {
    @PdcDocument(marker = "identity")
    record State(@PdcField(key = "example/identity") UUID identity,
                 @PdcField(key = "example/note", required = false) String note) {}
}
