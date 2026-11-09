package org.blu3fishez.springpaper.jpa;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Optional YAML convenience. The consumer can use ordinary role-scoped EnableJpaRepositories instead. */
@Configuration(proxyBeanMethods = false)
@Conditional(JpaRepositoryScanning.Configured.class)
@EnableJpaRepositories(basePackages = "${spring.paper.jpa.repositories}")
class JpaRepositoryScanning {
    static final class Configured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            // 소비자가 표준 EnableJpaRepositories로 스캔을 선언해도 Paper 전용 placeholder가 먼저
            // 실패했다. YAML 스캔은 해당 속성을 명시한 경우에만 import하며 Spring Data에 위임한다.
            return context.getEnvironment().containsProperty("spring.paper.jpa.repositories");
        }
    }
}
