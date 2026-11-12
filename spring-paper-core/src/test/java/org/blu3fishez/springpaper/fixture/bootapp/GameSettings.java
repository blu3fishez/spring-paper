package org.blu3fishez.springpaper.fixture.bootapp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("game")
public record GameSettings(@DefaultValue("Welcome") String message) {}
