package org.blu3fishez.springpaper.security.connection.config;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.config.internal.DefaultBeans;
import org.blu3fishez.springpaper.security.connection.api.PaperSecurityFilterChain;
import org.blu3fishez.springpaper.security.connection.repository.InMemoryPaperSecuritySessionRepository;
import org.blu3fishez.springpaper.security.connection.service.PaperConnectionSecurityService;
import org.blu3fishez.springpaper.security.connection.spi.PaperAuthenticationEntryPoint;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecurityResultHandler;
import org.blu3fishez.springpaper.security.connection.spi.PaperSecuritySessionRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.context.annotation.*;
import org.springframework.core.MethodParameter;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.*;

/** Host-neutral typed Spring configuration, also importable by a consumer-owned Velocity context. */
@Configuration(proxyBeanMethods = false)
public class PaperConnectionSecurityConfiguration {
    public static final String SCHEDULER_BEAN_NAME = "paperSecurityScheduler";
    @Bean @Lazy @Fallback public Clock paperDefaultSecurityClock() { return Clock.systemUTC(); }
    @Bean @Lazy @Fallback public PaperSecuritySettings paperDefaultSecuritySettings(
            ObjectProvider<PaperConfiguration> configuration, ConfigurableEnvironment environment) {
        var config = configuration.getIfAvailable(() -> new PaperConfiguration(environment));
        return new PaperSecuritySettings(Duration.ofSeconds(config.integer("spring.paper.security.pending-timeout-seconds", 120)),
                Duration.ofSeconds(config.integer("spring.paper.security.operation-timeout-seconds", 10)),
                Duration.ofSeconds(config.integer("spring.paper.security.shutdown-timeout-seconds", 5)));
    }
    @Bean @Lazy @Fallback public PaperSecuritySessionRepository paperDefaultSecuritySessions(Clock clock) {
        return new InMemoryPaperSecuritySessionRepository(clock);
    }
    @Bean @Lazy @Fallback public AuthenticationManager paperDefaultAuthenticationManager(ObjectProvider<AuthenticationProvider> providers) {
        var selected = providers.orderedStream().toList();
        return selected.isEmpty() ? request -> { throw new ProviderNotFoundException("No consumer AuthenticationProvider supports this request"); }
                : new ProviderManager(selected);
    }
    @Bean @Lazy @Fallback public PaperAuthenticationEntryPoint paperDefaultEntryPoint() {
        return pending -> CompletableFuture.completedFuture(null);
    }
    @Bean @Lazy @Fallback public PaperSecurityResultHandler paperDefaultResultHandler() {
        return (result, failure) -> CompletableFuture.completedFuture(null);
    }
    @Bean @Lazy @Fallback public PaperSecurityFilterChain paperDefaultSecurityFilterChain() { return new PaperSecurity().build(); }
    @Bean(defaultCandidate = false) @Lazy @Fallback @Qualifier(SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler paperDefaultSecurityScheduler(Clock clock) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("spring-paper-security-");
        scheduler.setDaemon(true); scheduler.setRemoveOnCancelPolicy(true); scheduler.setClock(clock);
        return scheduler;
    }
    @Bean @Fallback public PaperConnectionSecurityService paperDefaultConnectionSecurityService(
            PaperSecuritySessionRepository sessions, List<PaperSecurityFilterChain> chains, Clock clock,
            @Qualifier(SCHEDULER_BEAN_NAME) TaskScheduler scheduler, PaperSecuritySettings settings,
            PaperAuthenticationEntryPoint entry, PaperSecurityResultHandler results,
            org.springframework.context.ApplicationEventPublisher events) {
        return new PaperConnectionSecurityService(sessions, chains, clock, scheduler, settings, entry, results, events);
    }
    @Bean public static BeanFactoryPostProcessor paperConnectionSecurityDefaults() {
        return beans -> {
            DefaultBeans.backOff(beans, Clock.class, "paperDefaultSecurityClock", "paperSecurityClock");
            DefaultBeans.backOff(beans, PaperSecuritySettings.class, "paperDefaultSecuritySettings", "paperSecuritySettings");
            DefaultBeans.backOff(beans, PaperSecuritySessionRepository.class, "paperDefaultSecuritySessions", "paperSecuritySessionRepository");
            DefaultBeans.backOff(beans, AuthenticationManager.class, "paperDefaultAuthenticationManager", "paperAuthenticationManager");
            DefaultBeans.backOff(beans, PaperAuthenticationEntryPoint.class, "paperDefaultEntryPoint", "paperAuthenticationEntryPoint");
            DefaultBeans.backOff(beans, PaperSecurityResultHandler.class, "paperDefaultResultHandler", "paperSecurityResultHandler");
            DefaultBeans.backOff(beans, PaperSecurityFilterChain.class, "paperDefaultSecurityFilterChain", null);
            DefaultBeans.backOff(beans, PaperConnectionSecurityService.class, "paperDefaultConnectionSecurityService", "paperConnectionSecurityService");
            try {
                var method = PaperConnectionSecurityConfiguration.class.getMethod("paperDefaultConnectionSecurityService",
                        PaperSecuritySessionRepository.class, List.class, Clock.class, TaskScheduler.class,
                        PaperSecuritySettings.class, PaperAuthenticationEntryPoint.class, PaperSecurityResultHandler.class,
                        org.springframework.context.ApplicationEventPublisher.class);
                var dependency = new DependencyDescriptor(new MethodParameter(method, 3), true, false);
                DefaultBeans.backOff(beans, dependency, "paperDefaultSecurityScheduler");
                if (beans.containsBeanDefinition("paperDefaultSecurityScheduler") && !beans.containsBean(SCHEDULER_BEAN_NAME))
                    ((org.springframework.beans.factory.support.BeanDefinitionRegistry) beans)
                            .registerAlias("paperDefaultSecurityScheduler", SCHEDULER_BEAN_NAME);
            } catch (NoSuchMethodException impossible) { throw new IllegalStateException(impossible); }
        };
    }
}
