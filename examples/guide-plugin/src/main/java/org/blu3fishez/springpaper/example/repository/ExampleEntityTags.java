package org.blu3fishez.springpaper.example.repository;

import java.util.UUID;
import org.blu3fishez.springpaper.pdc.*;
import org.bukkit.entity.Entity;

@PaperPdcRepository
public interface ExampleEntityTags extends PdcRepository<Entity, ExampleEntityTags.State> {
    @PdcDocument(marker = "island")
    record State(@PdcField(key = "example/island") long island,
                 @PdcField(key = "example/owner") UUID owner) {}
}
