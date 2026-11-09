package org.blu3fishez.springpaper.example.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import net.kyori.adventure.text.Component;
import org.blu3fishez.springpaper.example.repository.ExampleEntityTags;
import org.blu3fishez.springpaper.example.repository.ExampleItemTags;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Marker;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.stereotype.Service;

/** Invoked by the console command on the real Paper thread; no API-only ItemMeta stand-in. */
@Service
@RequiredArgsConstructor
public final class ExamplePdcVerificationService {
    private final JavaPlugin plugin;
    private final ExampleEntityTags entities;
    private final ExampleItemTags items;

    public String verify() {
        UUID owner = UUID.fromString("3342edc1-2e3f-4f5a-b745-8e30c02b9ddd");
        var world = plugin.getServer().getWorlds().getFirst();
        var entity = world.spawn(world.getSpawnLocation(), Marker.class);
        try {
            entity.getPersistentDataContainer().set(key("example/island"), PersistentDataType.LONG, 82L);
            entity.getPersistentDataContainer().set(key("example/owner"), PersistentDataType.STRING, owner.toString());
            require(entities.read(entity).value().orElseThrow().equals(new ExampleEntityTags.State(82, owner)), "legacy entity mapping");
            entities.write(entity, new ExampleEntityTags.State(83, owner));
            require(entity.getPersistentDataContainer().get(key("example/island"), PersistentDataType.LONG) == 83L, "entity write");
            require(entity.getPersistentDataContainer().get(key("example/owner"), PersistentDataType.STRING).equals(owner.toString()), "UUID STRING contract");
        } finally { entity.remove(); }

        ItemStack item = ItemStack.of(Material.PAPER);
        Component name = Component.text("PDC smoke");
        item.editMeta(meta -> meta.displayName(name));
        item.editPersistentDataContainer(data -> data.set(key("example/foreign"), PersistentDataType.INTEGER, 91));
        items.write(item, new ExampleItemTags.State(owner, "original"));
        require(items.read(item).value().orElseThrow().equals(new ExampleItemTags.State(owner, "original")), "item commit");
        ItemStack copy = item.clone();
        items.write(copy, new ExampleItemTags.State(owner, null));
        require(!copy.getPersistentDataContainer().has(key("example/note")), "optional field removal");
        require(item.getPersistentDataContainer().get(key("example/note"), PersistentDataType.STRING).equals("original"), "item clone isolation");
        items.remove(copy);
        require(!items.exists(copy) && copy.getPersistentDataContainer().get(key("example/foreign"), PersistentDataType.INTEGER) == 91, "owned-key removal");
        require(name.equals(copy.getItemMeta().displayName()), "unrelated ItemMeta preservation");
        return "pdc=entity+item+legacy+clone+remove";
    }
    private NamespacedKey key(String value) { return new NamespacedKey(plugin, value); }
    private static void require(boolean condition, String check) {
        if (!condition) throw new IllegalStateException("PDC verification failed: " + check);
    }
}
