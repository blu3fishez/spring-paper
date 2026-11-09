package org.blu3fishez.springpaper.bootstrap;

import java.util.Map;
import java.util.logging.Logger;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.config.PaperServerInfo;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.blu3fishez.springpaper.task.PaperTasks;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

@Configuration(proxyBeanMethods = false)
public class PaperCoreConfiguration {
    public static final String WORKERS_BEAN_NAME = "paperTasks";
    private final JavaPlugin plugin;
    private final Server server;
    private final Logger logger;
    private final PaperConfiguration configuration;
    private final PaperServerInfo serverInfo;
    private final PaperThreadAccess threadAccess;

    private PaperCoreConfiguration(JavaPlugin plugin, PaperConfiguration configuration) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getLogger();
        this.configuration = configuration;
        this.serverInfo = new PaperServerInfo(server.getMinecraftVersion(), server.getBukkitVersion(),
                server.getVersion(), Runtime.version().toString());
        this.threadAccess = server::isPrimaryThread;
    }

    /** Captures Paper-owned objects on the server thread before initializers and context refresh. */
    public static void registerHost(AnnotationConfigApplicationContext context, JavaPlugin plugin,
            PaperConfiguration configuration) {
        var captured = new PaperCoreConfiguration(plugin, configuration);
        String configurationName = PaperCoreConfiguration.class.getName();
        context.registerBean(configurationName, PaperCoreConfiguration.class, () -> captured);
        Map<String, Object> hosts = Map.of("paperPlugin", captured.paperPlugin(), "paperServer", captured.paperServer(),
                "paperLogger", captured.paperLogger(), "paperConfiguration", captured.paperConfiguration(),
                "paperServerInfo", captured.paperServerInfo(), "paperThreadAccess", captured.paperThreadAccess());
        hosts.forEach(context.getBeanFactory()::registerSingleton);
        context.addBeanFactoryPostProcessor(beans -> hosts.forEach((name, instance) -> {
            var definition = beans.getBeanDefinition(name);
            if (!configurationName.equals(definition.getFactoryBeanName())
                    || !name.equals(definition.getFactoryMethodName())) {
                throw new IllegalArgumentException("Reserved Spring Paper bean name: " + name);
            }
            // Spring 7의 registerBeanDefinition은 같은 이름의 기존 singleton을 reset한다.
            // 초기화기에서 이미 노출한 객체를 설정 파싱 후 다시 연결해야 동일 인스턴스를 유지하며,
            // Paper가 소유한 plugin/server에 Spring 초기화·프록시·종료 콜백을 적용하지 않는다.
            ((RootBeanDefinition) definition).setTargetType(instance.getClass());
            Object existing = beans.getSingleton(name);
            if (existing == null) beans.registerSingleton(name, instance);
            else if (existing != instance) throw new IllegalArgumentException("Reserved Spring Paper singleton: " + name);
        }));
    }

    // 타입이 명시된 실제 @Bean 정의를 제공해 IDE와 Spring의 타입 탐색이 같은 계약을 보게 한다.
    // 런타임에서는 registerHost가 연결한 외부 singleton을 사용하므로 Paper 객체를 새로 만들지 않는다.
    @Bean(destroyMethod = "") public JavaPlugin paperPlugin() { return plugin; }
    @Bean(destroyMethod = "") public Server paperServer() { return server; }
    @Bean(destroyMethod = "") public Logger paperLogger() { return logger; }
    @Bean(destroyMethod = "") public PaperConfiguration paperConfiguration() { return configuration; }
    @Bean(destroyMethod = "") public PaperServerInfo paperServerInfo() { return serverInfo; }
    @Bean(destroyMethod = "") public PaperThreadAccess paperThreadAccess() { return threadAccess; }

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
