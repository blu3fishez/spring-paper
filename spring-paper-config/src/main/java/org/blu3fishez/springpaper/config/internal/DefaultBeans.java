package org.blu3fishez.springpaper.config.internal;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.core.ResolvableType;

/** Internal integration support, not a consumer extension point. Spring owns candidate matching/selection. */
public final class DefaultBeans {
    private static final Field DEPENDENCY;
    private Object dependency;
    static {
        try { DEPENDENCY = DefaultBeans.class.getDeclaredField("dependency"); }
        catch (NoSuchFieldException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
    private DefaultBeans() {}

    public static DependencyDescriptor dependency(Class<?> type) {
        // DependencyDescriptor's public construction API accepts field/parameter metadata. Only the
        // requested type is replaced; qualifiers/defaultCandidate are interpreted by Spring's resolver.
        return new DependencyDescriptor(DEPENDENCY, true, false) {
            @Override public Class<?> getDependencyType() { return type; }
            @Override public String getDependencyName() { return null; }
            @Override public ResolvableType getResolvableType() { return ResolvableType.forClass(type); }
        };
    }
    public static List<String> candidates(ConfigurableListableBeanFactory factory, Class<?> type) {
        return candidates(factory, dependency(type));
    }
    public static List<String> candidates(ConfigurableListableBeanFactory factory, DependencyDescriptor dependency) {
        return Arrays.stream(BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                        factory, dependency.getDependencyType(), true, false))
                .filter(name -> factory.isAutowireCandidate(name, dependency)).toList();
    }
    public static void backOff(ConfigurableListableBeanFactory factory, Class<?> type, String ownName, String alias) {
        backOff(factory, dependency(type), ownName);
        var registry = (BeanDefinitionRegistry) factory;
        if (alias != null && factory.containsBeanDefinition(ownName) && !factory.containsBean(alias)) {
            registry.registerAlias(ownName, alias);
        }
    }
    public static void backOff(ConfigurableListableBeanFactory factory, DependencyDescriptor dependency, String ownName) {
        if (!factory.containsBeanDefinition(ownName)) return;
        if (candidates(factory, dependency).stream().anyMatch(name -> !name.equals(ownName))) {
            ((BeanDefinitionRegistry) factory).removeBeanDefinition(ownName);
        }
    }
}
