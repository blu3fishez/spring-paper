package org.blu3fishez.springpaper.examples.minimal.service;

import java.util.logging.Logger;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public final class GreetingService {
    private final String greeting;
    private final Logger logger;
    public GreetingService(@Value("${example.greeting}") String greeting,
            @Value("${example.initialized}") boolean initialized, Logger logger,
            @Qualifier("exampleModuleBanner") ObjectProvider<String> banner) {
        if (!initialized) throw new IllegalStateException("Spring initializer did not run");
        this.greeting = greeting + " " + banner.getIfAvailable(() -> "module=disabled");
        this.logger = logger;
    }
    @OnPaperMainThreadStartup public void start() { logger.info("MINIMAL_READY"); }
    public String greet(String name) { return greeting + ", " + name + "!"; }
    @OnPaperMainThreadShutdown public void stop() { logger.info("MINIMAL_STOPPED"); }
}
