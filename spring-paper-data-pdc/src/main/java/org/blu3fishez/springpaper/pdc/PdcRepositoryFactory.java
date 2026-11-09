package org.blu3fishez.springpaper.pdc;

import lombok.RequiredArgsConstructor;

import io.papermc.paper.persistence.PersistentDataContainerView;
import java.util.function.Consumer;
import org.blu3fishez.springpaper.pdc.internal.DefaultPdcRepository;
import org.blu3fishez.springpaper.pdc.internal.PdcHolderAdapter;
import org.blu3fishez.springpaper.pdc.internal.PdcRecordMapper;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.beans.factory.BeanFactory;

/** Public programmatic alternative to annotated repository interfaces. Each result owns immutable mapping metadata. */
@RequiredArgsConstructor
public final class PdcRepositoryFactory {
    private final JavaPlugin plugin;
    private final PaperThreadAccess thread;
    private final BeanFactory beans;

    public <T> PdcRepository<Entity, T> entity(Class<T> model) {
        return create(model, new PdcHolderAdapter<>() {
            @Override public PersistentDataContainerView view(Entity holder) { return holder.getPersistentDataContainer(); }
            @Override public void edit(Entity holder, Consumer<PersistentDataContainer> action) { action.accept(holder.getPersistentDataContainer()); }
        });
    }
    public <T> PdcRepository<ItemStack, T> item(Class<T> model) {
        return create(model, new PdcHolderAdapter<>() {
            @Override public PersistentDataContainerView view(ItemStack holder) { return holder.getPersistentDataContainer(); }
            @Override public void edit(ItemStack holder, Consumer<PersistentDataContainer> action) {
                // ItemStack's view is read-only. Paper commits edits; retaining or mutating that view loses data.
                if (holder.isEmpty()) throw new IllegalArgumentException("Cannot write PDC to an empty ItemStack");
                holder.editPersistentDataContainer(action);
            }
        });
    }
    public <T> PdcRepository<PersistentDataContainer, T> container(Class<T> model) {
        return create(model, new PdcHolderAdapter<>() {
            @Override public PersistentDataContainerView view(PersistentDataContainer holder) { return holder; }
            @Override public void edit(PersistentDataContainer holder, Consumer<PersistentDataContainer> action) { action.accept(holder); }
        });
    }
    private <H, T> PdcRepository<H, T> create(Class<T> model, PdcHolderAdapter<H> holder) {
        return new DefaultPdcRepository<>(new PdcRecordMapper<>(model, plugin, beans), holder, thread);
    }
}
