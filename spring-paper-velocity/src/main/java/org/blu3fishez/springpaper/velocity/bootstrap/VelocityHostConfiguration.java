package org.blu3fishez.springpaper.velocity.bootstrap;

import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/** Actual native host beans. Import this configuration so tooling sees the same definitions as runtime. */
@Configuration(proxyBeanMethods = false)
public class VelocityHostConfiguration {
    public static final String PLUGIN_BEAN_NAME = "velocityPlugin";
    public static final String PROXY_BEAN_NAME = "velocityProxyServer";
    public static final String LOGGER_BEAN_NAME = "velocityLogger";
    public static final String DATA_DIRECTORY_BEAN_NAME = "velocityDataDirectory";
    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private VelocityHostConfiguration(Object plugin, ProxyServer proxy, Logger logger, Path dataDirectory) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    static void register(AnnotationConfigApplicationContext context, Object plugin, ProxyServer proxy,
            Logger logger, Path dataDirectory) {
        var captured = new VelocityHostConfiguration(plugin, proxy, logger, dataDirectory);
        String configurationName = VelocityHostConfiguration.class.getName();
        context.registerBean(configurationName, VelocityHostConfiguration.class, () -> captured);
        Map<String, Object> hosts = Map.of(PLUGIN_BEAN_NAME, plugin, PROXY_BEAN_NAME, proxy,
                LOGGER_BEAN_NAME, logger, DATA_DIRECTORY_BEAN_NAME, dataDirectory);
        hosts.forEach(context.getBeanFactory()::registerSingleton);
        context.addBeanFactoryPostProcessor(beans -> hosts.forEach((name, instance) -> {
            var definition = beans.getBeanDefinition(name);
            if (!configurationName.equals(definition.getFactoryBeanName())
                    || !name.equals(definition.getFactoryMethodName())) {
                throw new IllegalArgumentException("Reserved Velocity host bean name: " + name);
            }
            // Spring 7 resets an existing singleton when its @Bean definition is parsed. Reconnect
            // the captured object afterwards; native host objects never undergo Spring destruction.
            ((RootBeanDefinition) definition).setTargetType(instance.getClass());
            Object existing = beans.getSingleton(name);
            if (existing == null) beans.registerSingleton(name, instance);
            else if (existing != instance) throw new IllegalArgumentException("Reserved Velocity host singleton: " + name);
        }));
    }

    @Bean(destroyMethod = "") public Object velocityPlugin() { return plugin; }
    @Bean(destroyMethod = "") public ProxyServer velocityProxyServer() { return proxy; }
    @Bean(destroyMethod = "") public Logger velocityLogger() { return logger; }
    @Bean(destroyMethod = "") public Path velocityDataDirectory() { return dataDirectory; }
    @Bean public static PropertySourcesPlaceholderConfigurer velocityPlaceholders() {
        return new PropertySourcesPlaceholderConfigurer();
    }
}
