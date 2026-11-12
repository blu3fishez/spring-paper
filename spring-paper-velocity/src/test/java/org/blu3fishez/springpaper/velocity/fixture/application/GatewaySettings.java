package org.blu3fishez.springpaper.velocity.fixture.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway")
public record GatewaySettings(String greeting, String imported) {}
