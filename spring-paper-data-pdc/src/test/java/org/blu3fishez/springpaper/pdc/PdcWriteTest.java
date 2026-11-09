package org.blu3fishez.springpaper.pdc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Set;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class PdcWriteTest {
    enum Kind { MAP, COMPASS }
    @PdcDocument
    record ItemData(@PdcField(key = "item/kind") Kind kind,
                    @PdcField(key = "item/description", required = false) String description) {}

    @Test void commitsItemEditsAndRemovesOnlyOwnedKeysIncludingStaleOptionalValues() {
        var host = new PdcHost();
        var foreign = PdcHost.key("other/owner");
        host.data.set(foreign, PersistentDataType.INTEGER, 91);
        var repository = host.factory().item(ItemData.class);
        repository.write(host.item, new ItemData(Kind.COMPASS, "north"));
        assertEquals("COMPASS", host.data.get(PdcHost.key("item/kind"), PersistentDataType.STRING));
        assertEquals(new ItemData(Kind.COMPASS, "north"), repository.read(host.item).value().orElseThrow());
        repository.write(host.item, new ItemData(Kind.MAP, null));
        assertFalse(host.data.has(PdcHost.key("item/description")));
        assertEquals(new ItemData(Kind.MAP, null), repository.read(host.item).value().orElseThrow());
        repository.remove(host.item);
        assertEquals(Set.of(foreign), host.data.getKeys());
        assertEquals(91, host.data.get(foreign, PersistentDataType.INTEGER));
    }

    static final class RejectingType implements PersistentDataType<String, String> {
        @Override public Class<String> getPrimitiveType() { return String.class; }
        @Override public Class<String> getComplexType() { return String.class; }
        @Override public String toPrimitive(String value, PersistentDataAdapterContext context) {
            if (value.equals("explode")) throw new IllegalArgumentException("Cannot encode this value");
            return "encoded:" + value;
        }
        @Override public String fromPrimitive(String value, PersistentDataAdapterContext context) { return value.substring(8); }
    }
    @PdcDocument
    record Custom(@PdcField(key = "item/first") String first,
                  @PdcField(key = "item/custom", type = RejectingType.class) String custom) {}

    @Test void validatesAllCodecsBeforeMutatingLiveEntityData() {
        var host = new PdcHost();
        host.beans.registerSingleton("customType", new RejectingType());
        host.data.set(PdcHost.key("item/first"), PersistentDataType.STRING, "original");
        var repository = host.factory().entity(Custom.class);
        assertThrows(IllegalArgumentException.class, () -> repository.write(host.entity, new Custom("replacement", "explode")));
        assertEquals("original", host.data.get(PdcHost.key("item/first"), PersistentDataType.STRING));
        assertFalse(host.data.has(PdcHost.key("item/custom")));
        repository.write(host.entity, new Custom("replacement", "ok"));
        assertEquals("encoded:ok", host.data.get(PdcHost.key("item/custom"), PersistentDataType.STRING));
        assertEquals(new Custom("replacement", "ok"), repository.read(host.entity).value().orElseThrow());
    }

    @Test void rejectsWorkerAccessBeforeTouchingTheHolder() {
        var host = new PdcHost();
        var repository = host.factory().entity(ItemData.class);
        host.primary = false;
        assertThrows(IllegalStateException.class, () -> repository.read(host.entity));
        assertThrows(IllegalStateException.class, () -> repository.exists(host.entity));
        assertThrows(IllegalStateException.class, () -> repository.write(host.entity, new ItemData(Kind.MAP, null)));
        assertThrows(IllegalStateException.class, () -> repository.remove(host.entity));
        verify(host.entity, never()).getPersistentDataContainer();
    }

    @Test void refusesEmptyItemsAndNullRequiredValuesWithoutChanges() {
        var host = new PdcHost();
        var repository = host.factory().item(ItemData.class);
        assertThrows(IllegalArgumentException.class, () -> repository.write(host.item, new ItemData(null, null)));
        assertTrue(host.data.isEmpty());
        when(host.item.isEmpty()).thenReturn(true);
        assertThrows(IllegalArgumentException.class, () -> repository.write(host.item, new ItemData(Kind.MAP, null)));
        assertTrue(host.data.isEmpty());
    }
}
