package org.blu3fishez.springpaper.pdc.internal;

import org.blu3fishez.springpaper.pdc.PdcRepositoryFactory;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PdcConfiguration {
    @Bean PdcRepositoryFactory pdcRepositoryFactory(JavaPlugin plugin, ObjectProvider<PaperThreadAccess> thread, BeanFactory beans) {
        return new PdcRepositoryFactory(plugin,
                thread.getIfAvailable(() -> () -> plugin.getServer().isPrimaryThread()), beans);
    }
}
