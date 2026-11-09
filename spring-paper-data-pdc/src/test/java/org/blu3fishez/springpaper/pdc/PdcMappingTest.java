package org.blu3fishez.springpaper.pdc;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class PdcMappingTest {
    @PdcDocument record Duplicate(@PdcField(key = "same") int a, @PdcField(key = "same") int b) {}
    @PdcDocument record OptionalPrimitive(@PdcField(key = "a", required = false) int a) {}
    @PdcDocument(marker = "typo") record WrongMarker(@PdcField(key = "a") int a) {}
    @PdcDocument record MissingAnnotation(int a) {}

    @Test void rejectsAmbiguousOrIncompleteMappingsBeforeLiveAccess() {
        var factory = new PdcHost().factory();
        for (Class<?> type : new Class<?>[] {Duplicate.class, OptionalPrimitive.class, WrongMarker.class, MissingAnnotation.class}) {
            assertThrows(IllegalArgumentException.class, () -> factory.entity(type), type::getName);
        }
    }

    @PdcDocument record Validated(@PdcField(key = "positive") int positive) {
        Validated { if (positive <= 0) throw new IllegalArgumentException("positive value required"); }
    }

    @Test void reportsDocumentValidationWithoutDeletingRejectedData() {
        var host = new PdcHost();
        host.data.set(PdcHost.key("positive"), PersistentDataType.INTEGER, -7);
        var result = host.factory().container(Validated.class).read(host.data);
        assertEquals(PdcReadResult.Status.INVALID, result.status());
        assertEquals(PdcReadProblem.Kind.INVALID_DOCUMENT, result.problems().getFirst().kind());
        assertEquals(-7, host.data.get(PdcHost.key("positive"), PersistentDataType.INTEGER));
    }
}
