package org.blu3fishez.springpaper.pdc.fixture.valid;

import org.blu3fishez.springpaper.pdc.*;
import org.bukkit.inventory.ItemStack;

@PaperPdcRepository
public interface ItemTags extends PdcRepository<ItemStack, ItemTags.State> {
    @PdcDocument
    record State(@PdcField(key = "item/type") String type) {}
}
