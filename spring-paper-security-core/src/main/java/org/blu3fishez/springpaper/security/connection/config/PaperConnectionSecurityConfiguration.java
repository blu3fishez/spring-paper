package org.blu3fishez.springpaper.security.connection.config;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.connection.spi.PaperAuthenticationEntryPoint;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecuritySessionRepository;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.ProviderNotFoundException;

/** Host-neutral Spring configuration, also importable by a consumer-owned Velocity context. */
@Configuration(proxyBeanMethods = false)
public class PaperConnectionSecurityConfiguration {
    @Bean public static BeanFactoryPostProcessor paperConnectionSecurityDefaults() {
        return beans -> {
            BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beans;
            // Boot 조건부 구성 없이 소비자 @Bean이 모두 파싱된 뒤 타입으로 back off한다.
            // 초기 인프라 탐색은 allowEagerInit=false로 하여 provider/repository를 PostProcessor보다 먼저 만들지 않는다.
            fallback(beans, registry, Clock.class, "paperSecurityClock", Clock::systemUTC);
            fallback(beans, registry, PaperSecuritySettings.class, "paperSecuritySettings", () -> {
                var config = beans.getBeanProvider(PaperConfiguration.class).getIfAvailable(
                        () -> new PaperConfiguration(beans.getBean(ConfigurableEnvironment.class)));
                return new PaperSecuritySettings(Duration.ofSeconds(config.integer("spring.paper.security.pending-timeout-seconds", 120)),
                        Duration.ofSeconds(config.integer("spring.paper.security.operation-timeout-seconds", 10)),
                        Duration.ofSeconds(config.integer("spring.paper.security.shutdown-timeout-seconds", 5)));
            });
            fallback(beans, registry, PaperSecuritySessionRepository.class, "paperSecuritySessionRepository",
                    () -> new InMemoryPaperSecuritySessionRepository(beans.getBean(Clock.class)));
            fallback(beans, registry, AuthenticationManager.class, "paperAuthenticationManager", () -> {
                var providers = beans.getBeanProvider(AuthenticationProvider.class).orderedStream().toList();
                return providers.isEmpty() ? request -> { throw new ProviderNotFoundException("No consumer AuthenticationProvider supports this request"); }
                        : new ProviderManager(providers);
            });
            fallback(beans, registry, PaperAuthenticationEntryPoint.class, "paperAuthenticationEntryPoint",
                    () -> pending -> CompletableFuture.completedFuture(null));
            fallback(beans, registry, PaperSecurityResultHandler.class, "paperSecurityResultHandler",
                    () -> (result, failure) -> CompletableFuture.completedFuture(null));
            fallback(beans, registry, PaperSecurityFilterChain.class, "paperDefaultSecurityFilterChain", () -> new PaperSecurity().build());
            if (!hasDefaultScheduler(beans)) {
                var definition = new RootBeanDefinition(ThreadPoolTaskScheduler.class);
                definition.getPropertyValues().add("poolSize", 1).add("threadNamePrefix", "spring-paper-security-")
                        .add("daemon", true).add("removeOnCancelPolicy", true).add("clock", new RuntimeBeanReference(Clock.class));
                definition.setDestroyMethodName("shutdown");
                registry.registerBeanDefinition("paperSecurityScheduler", definition);
            }
            if (beans.getBeanNamesForType(PaperConnectionSecurityService.class, false, false).length == 0) {
                var definition = new RootBeanDefinition(PaperConnectionSecurityService.class);
                definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
                registry.registerBeanDefinition("paperConnectionSecurityService", definition);
            }
        };
    }
    private static boolean hasDefaultScheduler(ConfigurableListableBeanFactory beans) {
        for (String name : beans.getBeanNamesForType(TaskScheduler.class, false, false)) {
            if (!beans.containsBeanDefinition(name)) return true;
            var definition = beans.getBeanDefinition(name);
            // Spring's qualifier-only beans are not candidates for an unqualified constructor.
            // Respect that contract rather than coupling security to a Paper scheduler name/type.
            if (definition.isAutowireCandidate() && (!(definition instanceof AbstractBeanDefinition candidate)
                    || candidate.isDefaultCandidate())) return true;
        }
        return false;
    }
    private static <T> void fallback(ConfigurableListableBeanFactory beans, BeanDefinitionRegistry registry,
                                    Class<T> type, String name, Supplier<T> factory) {
        if (beans.getBeanNamesForType(type, false, false).length == 0) {
            registry.registerBeanDefinition(name, new RootBeanDefinition(type, factory));
        }
    }
}
