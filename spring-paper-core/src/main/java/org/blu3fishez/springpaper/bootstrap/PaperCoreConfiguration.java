package org.blu3fishez.springpaper.bootstrap;

import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

@Configuration(proxyBeanMethods = false)
public class PaperCoreConfiguration {
    public static final String WORKERS_BEAN_NAME = "paperTasks";
    @Bean public static PaperBeanCatalog paperBeanCatalog() { return new PaperBeanCatalog(); }
    @Bean public static PropertySourcesPlaceholderConfigurer paperPlaceholders() {
        return new PropertySourcesPlaceholderConfigurer();
    }
    @Bean(WORKERS_BEAN_NAME) public PaperTasks paperTasks(JavaPlugin plugin, PaperConfiguration configuration) {
        return new PaperTasks(plugin, configuration);
    }
}
