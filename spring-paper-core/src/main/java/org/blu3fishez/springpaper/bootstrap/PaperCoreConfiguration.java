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
    // PostProcessor를 만드는 @Bean은 static으로 선언해 이 configuration과 그 의존 빈의 조기 생성을 피한다.
    // 후보 발견은 metadata 단계에서 하고, Paper 등록은 refresh가 끝난 뒤 bootstrap이 메인 스레드에서 수행한다.
    @Bean public static PaperBeanCatalog paperBeanCatalog() { return new PaperBeanCatalog(); }
    @Bean public static PropertySourcesPlaceholderConfigurer paperPlaceholders() {
        return new PropertySourcesPlaceholderConfigurer();
    }
    @Bean(WORKERS_BEAN_NAME) public PaperTasks paperTasks(JavaPlugin plugin, PaperConfiguration configuration) {
        return new PaperTasks(plugin, configuration);
    }
}
