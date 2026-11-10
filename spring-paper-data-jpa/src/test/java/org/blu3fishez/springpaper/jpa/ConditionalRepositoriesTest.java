package org.blu3fishez.springpaper.jpa;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.jpa.fixture.NoteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

class ConditionalRepositoriesTest {
    public static class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty("feature.notes", Boolean.class, false);
        }
    }
    @Configuration(proxyBeanMethods = false) @Conditional(Enabled.class) @Import(JpaConfiguration.class)
    @EnableJpaRepositories(basePackageClasses = NoteRepository.class)
    static class FeatureRepositories {}
    @Configuration(proxyBeanMethods = false) @Import(JpaConfiguration.class)
    @EnableJpaRepositories(basePackageClasses = NoteRepository.class)
    static class IndependentRepositories {}

    @Test void theConditionGuardsItsRepositoryRegistrarNotIndependentRegistrationPaths() {
        for (boolean enabled : new boolean[] {false, true}) {
            for (boolean independent : new boolean[] {false, true}) {
                if (enabled && independent) continue; // Duplicate registrars are not an application pattern.
                try (var context = new AnnotationConfigApplicationContext()) {
                    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                            "feature.notes", enabled,
                            "spring.paper.jpa.entities", NoteRepository.class.getPackageName(),
                            "spring.paper.jpa.datasource.url", "jdbc:h2:mem:" + UUID.randomUUID(),
                            "spring.paper.jpa.properties.hibernate.hbm2ddl.auto", "create-drop")));
                    context.registerBean(PaperConfiguration.class, () -> new PaperConfiguration(context.getEnvironment()));
                    context.register(FeatureRepositories.class);
                    if (independent) context.register(IndependentRepositories.class);
                    context.refresh();
                    if (!enabled && !independent) assertFalse(context.getBeanFactory().containsSingleton("springPaperDataSource"));
                    assertEquals(enabled || independent, context.getBeanNamesForType(NoteRepository.class).length == 1);
                    if (!enabled && !independent) assertFalse(context.getBeanFactory().containsSingleton("springPaperDataSource"));
                }
            }
        }
    }
}
