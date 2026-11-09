package org.blu3fishez.springpaper.pdc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class EntityRepositoryTest {
    @PdcDocument(marker = "island")
    record Broker(@PdcField(key = "black_market/broker_island") long island,
                  @PdcField(key = "black_market/broker_owner") UUID owner) {}

    @Test void readsTheExistingLongAndUuidStringWithoutRewritingTheEntity() {
        var plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("SimpleExtraction");
        when(plugin.namespace()).thenReturn("simpleextraction");
        var entity = mock(Entity.class);
        var data = mock(PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        var island = new NamespacedKey("simpleextraction", "black_market/broker_island");
        var owner = new NamespacedKey("simpleextraction", "black_market/broker_owner");
        var identity = UUID.fromString("f3c2b05a-9ff9-4f5f-9d25-041938597dfa");
        when(data.has(island)).thenReturn(true);
        when(data.has(owner)).thenReturn(true);
        when(data.has(island, PersistentDataType.LONG)).thenReturn(true);
        when(data.has(owner, PersistentDataType.STRING)).thenReturn(true);
        when(data.get(island, PersistentDataType.LONG)).thenReturn(82L);
        when(data.get(owner, PersistentDataType.STRING)).thenReturn(identity.toString());
        var repository = new PdcRepositoryFactory(plugin, () -> true, new DefaultListableBeanFactory()).entity(Broker.class);

        assertTrue(repository.exists(entity));
        assertEquals(new Broker(82, identity), repository.read(entity).value().orElseThrow());
        verify(data, never()).set(any(), any(), any());
        verify(data, never()).remove(any(NamespacedKey.class));
    }

    @Test void distinguishesAbsentMissingMalformedAndWrongTypeWithoutRepair() {
        var host = new PdcHost();
        var repository = host.factory().entity(Broker.class);
        assertEquals(PdcReadResult.Status.ABSENT, repository.read(host.entity).status());
        host.data.set(PdcHost.key("black_market/broker_island"), PersistentDataType.LONG, 82L);
        assertTrue(repository.exists(host.entity));
        assertEquals(PdcReadProblem.Kind.MISSING_FIELD, repository.read(host.entity).problems().getFirst().kind());
        host.data.set(PdcHost.key("black_market/broker_owner"), PersistentDataType.STRING, "broken-uuid");
        assertEquals(PdcReadProblem.Kind.INVALID_VALUE, repository.read(host.entity).problems().getFirst().kind());
        assertEquals("broken-uuid", host.data.get(PdcHost.key("black_market/broker_owner"), PersistentDataType.STRING));
        host.data.set(PdcHost.key("black_market/broker_owner"), PersistentDataType.INTEGER, 7);
        assertEquals(PdcReadProblem.Kind.TYPE_MISMATCH, repository.read(host.entity).problems().getFirst().kind());
    }
}
