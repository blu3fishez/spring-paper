package org.blu3fishez.springpaper.pdc.fixture.invalid;

import org.blu3fishez.springpaper.pdc.*;
import org.blu3fishez.springpaper.pdc.fixture.valid.BrokerTags;
import org.bukkit.entity.Entity;

@PaperPdcRepository
public interface WrongHolderOverload extends PdcRepository<Entity, BrokerTags.BrokerData> {
    PdcReadResult<BrokerTags.BrokerData> read(String wrongHolder);
}
