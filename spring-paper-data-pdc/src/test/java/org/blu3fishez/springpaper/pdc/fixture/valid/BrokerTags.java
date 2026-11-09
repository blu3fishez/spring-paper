package org.blu3fishez.springpaper.pdc.fixture.valid;

import java.util.Optional;
import java.util.UUID;
import org.blu3fishez.springpaper.pdc.*;
import org.bukkit.entity.Entity;

@PaperPdcRepository
public interface BrokerTags extends PdcRepository<Entity, BrokerTags.BrokerData> {
    @PdcDocument(marker = "island")
    record BrokerData(@PdcField(key = "black_market/broker_island") long island,
                      @PdcField(key = "black_market/broker_owner") UUID owner) {}

    default Optional<UUID> owner(Entity entity) { return read(entity).value().map(BrokerData::owner); }
}
