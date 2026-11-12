package org.blu3fishez.springpaper.fixture.bootauto;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnProperty(name = "spring.paper.fixture.enabled", havingValue = "true", matchIfMissing = false)
public class NativeFeatureAutoConfiguration {
    public record NativeFlagMarker(String value) {}
    @Bean NativeFlagMarker nativeFlagMarker() { return new NativeFlagMarker("enabled-after-config-data"); }
}
