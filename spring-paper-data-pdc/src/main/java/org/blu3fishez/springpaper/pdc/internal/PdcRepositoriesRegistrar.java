package org.blu3fishez.springpaper.pdc.internal;

import lombok.Setter;

import java.util.LinkedHashSet;
import org.blu3fishez.springpaper.pdc.EnablePaperPdcRepositories;
import org.blu3fishez.springpaper.pdc.PaperPdcRepository;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

/** Definition-time discovery using Spring's resource loader, metadata filters and condition evaluation. */
public final class PdcRepositoriesRegistrar implements ImportBeanDefinitionRegistrar, ResourceLoaderAware, EnvironmentAware {
    private ResourceLoader resources;
    @Setter
    private Environment environment;
    @Override public void setResourceLoader(ResourceLoader resources) { this.resources = resources; }

    @Override public void registerBeanDefinitions(AnnotationMetadata importing, BeanDefinitionRegistry registry) {
        var attributes = importing.getAnnotationAttributes(EnablePaperPdcRepositories.class.getName());
        if (attributes == null) return;
        var packages = new LinkedHashSet<String>();
        for (String name : (String[]) attributes.get("basePackages")) packages.add(environment.resolveRequiredPlaceholders(name));
        for (Class<?> marker : (Class<?>[]) attributes.get("basePackageClasses")) packages.add(marker.getPackageName());
        if (packages.isEmpty()) packages.add(ClassUtils.getPackageName(importing.getClassName()));
        var scanner = new ClassPathScanningCandidateComponentProvider(false, environment) {
            @Override protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isIndependent() && definition.getMetadata().isInterface();
            }
        };
        scanner.setResourceLoader(resources);
        scanner.addIncludeFilter(new AnnotationTypeFilter(PaperPdcRepository.class));
        for (String name : packages) for (var candidate : scanner.findCandidateComponents(name)) {
            String typeName = candidate.getBeanClassName();
            Class<?> repository = ClassUtils.resolveClassName(typeName, resources.getClassLoader());
            if (registry.containsBeanDefinition(typeName)) {
                var existing = registry.getBeanDefinition(typeName);
                if (repository.equals(existing.getAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE))) continue;
                throw new IllegalArgumentException("PDC repository bean name is already owned: " + typeName);
            }
            var definition = new RootBeanDefinition(PdcRepositoryFactoryBean.class);
            definition.getConstructorArgumentValues().addIndexedArgumentValue(0, repository);
            definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            // Generic and interface injection must work without creating a FactoryBean during type discovery.
            definition.setAttribute(FactoryBean.OBJECT_TYPE_ATTRIBUTE, repository);
            registry.registerBeanDefinition(typeName, definition);
        }
    }
}
