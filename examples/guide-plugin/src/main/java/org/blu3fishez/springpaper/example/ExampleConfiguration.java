package org.blu3fishez.springpaper.example;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.blu3fishez.springpaper.pdc.EnablePaperPdcRepositories;
import java.util.List;
import org.blu3fishez.springpaper.example.listener.ExampleAfterListener;
import org.blu3fishez.springpaper.example.listener.ExampleConfiguredListener;
import org.blu3fishez.springpaper.example.listener.ExampleSignalEvent;
import org.blu3fishez.springpaper.spi.PaperEventOrderConfigurer;
import org.bukkit.event.EventPriority;
import org.springframework.context.annotation.Bean;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = ExampleConfiguration.class)
@EnablePaperPdcRepositories(basePackageClasses = ExampleConfiguration.class)
public class ExampleConfiguration {
    @Bean PaperEventOrderConfigurer composedEventOrder() {
        return order -> order.before(ExampleSignalEvent.class, EventPriority.NORMAL,
                ExampleAfterListener.class, ExampleConfiguredListener.class);
    }
}
