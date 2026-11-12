package org.blu3fishez.springpaper.fixture.bootapp;

import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.stereotype.Service;

@Service
public final class GreetingService {
    private final JavaPlugin host;
    private final GameSettings settings;
    public GreetingService(JavaPlugin host, GameSettings settings) { this.host = host; this.settings = settings; }
    public JavaPlugin host() { return host; }
    public String message() { return settings.message(); }
}
