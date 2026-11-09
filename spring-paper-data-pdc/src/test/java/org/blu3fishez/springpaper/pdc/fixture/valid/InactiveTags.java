package org.blu3fishez.springpaper.pdc.fixture.valid;

import org.blu3fishez.springpaper.pdc.*;
import org.bukkit.entity.Entity;
import org.springframework.context.annotation.Profile;

@Profile("pdc-inactive")
@PaperPdcRepository
public interface InactiveTags extends PdcRepository<Entity, BrokerTags.BrokerData> {}
