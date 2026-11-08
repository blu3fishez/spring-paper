package org.blu3fishez.springpaper.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class YamlDocumentTest {
    private YamlDocument yaml(String value) { return YamlDocument.read(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))); }
    @Test void overlaysScalarsAndListsWithoutLosingOtherSettings() {
        var document = yaml("spring:\n  paper:\n    minecraft:\n      version: '26.3'\n    names: [guide, status]\n")
                .overlay(yaml("spring:\n  paper:\n    minecraft:\n      version: '26.4'\n"));
        assertEquals("26.4", document.required("spring.paper.minecraft.version"));
        assertEquals(List.of("guide", "status"), document.strings("spring.paper.names"));
    }
    @Test void rejectsDuplicateKeysAndArbitraryObjectConstruction() {
        assertThrows(Exception.class, () -> yaml("name: first\nname: second\n"));
        assertThrows(Exception.class, () -> yaml("value: !!java.net.URL ['https://example.invalid']"));
    }
    @Test void rejectsMalformedBooleanAndNonMapRoot() {
        assertThrows(IllegalArgumentException.class, () -> yaml("enabled: maybe").bool("enabled", false));
        assertThrows(IllegalArgumentException.class, () -> yaml("[a,b]"));
    }
}
