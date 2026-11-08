package org.blu3fishez.springpaper.config;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PaperConfigurationTest {
    @Test void resolvesSelectedPropertiesAndListsWithoutRequiringUnrelatedRuntimeSecrets() {
        String source = "spring:\n  paper:\n    minecraft:\n      version: '${UNSET_SPRING_PAPER_TEST_VERSION:26.3}'\n    development:\n      jvm-args: ['-Xmx${UNSET_SPRING_PAPER_TEST_HEAP:1G}']\n    jpa:\n      password: '${UNSET_SPRING_PAPER_TEST_PASSWORD}'\n";
        var config = PaperConfiguration.of(YamlDocument.read(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))));
        assertEquals("26.3", config.required("spring.paper.minecraft.version"));
        assertEquals(List.of("-Xmx1G"), config.strings("spring.paper.development.jvm-args"));
        assertThrows(IllegalArgumentException.class, () -> config.required("spring.paper.jpa.password"));
    }
}
