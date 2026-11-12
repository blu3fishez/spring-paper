package org.blu3fishez.springpaper.examples.bootvelocity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("gateway")
public record GatewaySettings(@DefaultValue("Welcome") String greeting) {}
