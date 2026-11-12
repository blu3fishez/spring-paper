package org.blu3fishez.springpaper.fixture.bootlegacy;

import org.blu3fishez.springpaper.annotation.SpringPaperPluginApplication;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@SpringPaperPluginApplication(name = "Legacy", configuration = "legacy.yaml",
        configurations = LegacyCarrier.Features.class, initializers = LegacyCarrier.Capture.class)
public class LegacyCarrier extends JavaPlugin {
    public record Captured(JavaPlugin host, Thread thread, String message) {}
    public static class Capture implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) {
            context.getBeanFactory().registerSingleton("legacyCaptured", new Captured(
                    (JavaPlugin) context.getBeanFactory().getSingleton("paperPlugin"), Thread.currentThread(),
                    context.getEnvironment().getProperty("game.message")));
        }
    }
    @Configuration(proxyBeanMethods = false)
    public static class Features {
        @Bean String legacyMessage(Captured captured) { return captured.message(); }
    }
}
