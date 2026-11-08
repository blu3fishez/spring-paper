package org.blu3fishez.springpaper.bootstrap;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.blu3fishez.springpaper.annotation.PaperStart;
import org.blu3fishez.springpaper.annotation.PaperStop;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** Discovery only. Paper side effects happen after refresh, on the platform thread. */
public final class PaperBeanCatalog implements BeanPostProcessor, BeanFactoryAware {
    private final Map<String, Class<?>> candidates = new LinkedHashMap<>();
    private ConfigurableListableBeanFactory factory;

    @Override public void setBeanFactory(BeanFactory factory) {
        this.factory = (ConfigurableListableBeanFactory) factory;
    }

    @Override public Object postProcessBeforeInitialization(Object bean, String beanName) {
        Class<?> type = bean.getClass();
        boolean stop = false;
        boolean lifecycle = false;
        for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
            stop |= method.isAnnotationPresent(PaperStop.class);
            lifecycle |= method.isAnnotationPresent(PaperStart.class) || method.isAnnotationPresent(PaperStop.class);
        }
        if (stop) {
            if (bean instanceof DisposableBean) throw new IllegalArgumentException(beanName + ": use one shutdown owner, PaperStop or DisposableBean");
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (Arrays.stream(method.getAnnotations()).anyMatch(a -> a.annotationType().getName().equals("jakarta.annotation.PreDestroy"))) {
                    throw new IllegalArgumentException(beanName + ": use one shutdown owner, PaperStop or PreDestroy");
                }
            }
            if (factory.containsBeanDefinition(beanName)
                    && factory.getBeanDefinition(beanName) instanceof AbstractBeanDefinition definition) {
                definition.setDestroyMethodName("");
                if (factory.getMergedBeanDefinition(beanName) instanceof RootBeanDefinition merged) {
                    merged.setDestroyMethodName("");
                    merged.registerExternallyManagedDestroyMethod("close");
                    merged.registerExternallyManagedDestroyMethod("shutdown");
                }
            }
        }
        if (lifecycle || AnnotatedElementUtils.hasAnnotation(type, PaperEventListener.class)
                || AnnotatedElementUtils.hasAnnotation(type, PaperCommandExecutor.class)) candidates.put(beanName, type);
        return bean;
    }

    public Map<String, Class<?>> candidates() { return Map.copyOf(candidates); }
}
