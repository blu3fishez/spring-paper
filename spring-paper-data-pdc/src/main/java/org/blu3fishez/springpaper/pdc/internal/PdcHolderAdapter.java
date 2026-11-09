package org.blu3fishez.springpaper.pdc.internal;

import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.function.Consumer;
import org.bukkit.persistence.PersistentDataContainer;

/** Actual variation: entities expose live containers, while ItemStack edits must be committed by Paper. */
public interface PdcHolderAdapter<H> {
    PersistentDataContainerView view(H holder);
    void edit(H holder, Consumer<PersistentDataContainer> action);
}
