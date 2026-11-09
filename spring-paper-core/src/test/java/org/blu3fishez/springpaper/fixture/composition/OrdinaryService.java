package org.blu3fishez.springpaper.fixture.composition;

import lombok.RequiredArgsConstructor;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public final class OrdinaryService {
    private final JavaPlugin plugin;
    private final OrdinaryDependency dependency;

    public String describe() { return plugin.getName() + ":" + dependency.value(); }
}
