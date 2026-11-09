package org.blu3fishez.springpaper.pdc;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.blu3fishez.springpaper.pdc.fixture.valid.BrokerTags;
import org.blu3fishez.springpaper.pdc.fixture.invalid.WrongHolderOverload;
import org.blu3fishez.springpaper.spi.PaperThreadAccess;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

class RepositoryConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    @EnablePaperPdcRepositories(basePackageClasses = BrokerTags.class)
    static class Repositories {}

    @Configuration(proxyBeanMethods = false)
    @EnablePaperPdcRepositories(basePackageClasses = WrongHolderOverload.class)
    static class InvalidRepositories {}

    public record Consumer(PdcRepository<Entity, BrokerTags.BrokerData> repository) {}

    @Test void discoversSingletonRepositoryAndPreservesGenericInjectionAndDefaultMethods() {
        var host = new PdcHost();
        UUID owner = UUID.fromString("a1b0c67e-6e43-4990-a35c-8afaa8e3276f");
        host.data.set(PdcHost.key("black_market/broker_island"), PersistentDataType.LONG, 12L);
        host.data.set(PdcHost.key("black_market/broker_owner"), PersistentDataType.STRING, owner.toString());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("paperPlugin", host.plugin);
            context.getBeanFactory().registerSingleton("paperThreadAccess", (PaperThreadAccess) () -> host.primary);
            context.register(Repositories.class, Consumer.class);
            context.refresh();
            var repository = context.getBean(BrokerTags.class);
            assertSame(repository, context.getBean(Consumer.class).repository());
            assertSame(repository, context.getBean(BrokerTags.class));
            assertEquals(owner, repository.owner(host.entity).orElseThrow());
            assertEquals(2, context.getBeansOfType(PdcRepository.class).size());
            assertEquals(0, context.getBeanNamesForType(org.blu3fishez.springpaper.pdc.fixture.valid.InactiveTags.class).length);
        }
    }

    @Test void rejectsAnIncompatibleCrudOverloadDuringRefresh() {
        var host = new PdcHost();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getBeanFactory().registerSingleton("paperPlugin", host.plugin);
            context.getBeanFactory().registerSingleton("paperThreadAccess", (PaperThreadAccess) () -> host.primary);
            context.register(InvalidRepositories.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
        }
    }
}
