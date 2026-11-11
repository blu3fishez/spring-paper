package org.blu3fishez.springpaper.bootstrap;

import org.blu3fishez.springpaper.internal.PaperLifecycleAnnotations;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.stream.Stream;
import java.util.concurrent.ConcurrentHashMap;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** Discovery only. Paper side effects happen after refresh, on the platform thread. */
public final class PaperBeanCatalog implements MergedBeanDefinitionPostProcessor {
    private final Map<String, Class<?>> candidates = new ConcurrentHashMap<>();

    @Override public void postProcessMergedBeanDefinition(RootBeanDefinition definition, Class<?> type, String beanName) {
        // This callback describes a FactoryBean itself, not its product. Spring only applies
        // postProcessAfterInitialization to products; never transfer factory hooks to that product.
        if (FactoryBean.class.isAssignableFrom(type)) return;
        boolean stop = false;
        boolean lifecycle = false;
        for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
            lifecycle |= PaperLifecycleAnnotations.hook(method);
        }
        for (Method method : type.getMethods()) {
            if (!method.isBridge() && !method.isSynthetic()) stop |= PaperLifecycleAnnotations.shutdown(method);
        }
        if (stop) {
            if (DisposableBean.class.isAssignableFrom(type)) throw new IllegalArgumentException(beanName + ": use one shutdown owner, OnPaperMainThreadShutdown or DisposableBean");
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (Arrays.stream(method.getAnnotations()).anyMatch(a -> a.annotationType().getName().equals("jakarta.annotation.PreDestroy"))) {
                    throw new IllegalArgumentException(beanName + ": use one shutdown owner, OnPaperMainThreadShutdown or PreDestroy");
                }
            }
            // 회귀 검증에서 원본 definition만 바꾸면 캐시된 merged definition의 close 추론이 남아 이중 종료됐다.
            // InitDestroyAnnotationBeanPostProcessor처럼 실제 merged metadata에 외부 종료 소유권을 등록한다.
            // MergedBeanDefinitionPostProcessor 계약은 기반 클래스 속성의 변경을 허용하지 않으므로
            // setDestroyMethodName으로 우회하지 않는다. DisposableBeanAdapter가 이 등록을 확인해 중복 실행을 건너뛴다.
            definition.registerExternallyManagedDestroyMethod("close");
            definition.registerExternallyManagedDestroyMethod("shutdown");
            String[] declared = definition.getDestroyMethodNames();
            if (declared != null) {
                for (String method : declared) {
                    if (!AbstractBeanDefinition.INFER_METHOD.equals(method)) definition.registerExternallyManagedDestroyMethod(method);
                }
            }
        }
        // Spring도 findAnnotationOnBean에서 실제 factory method metadata를 확인한다.
        // 값/세션을 캡처한 기존 어댑터는 클래스 스캔 대신 @Bean에 등록 의도를 표현할 수 있다.
        Method factoryMethod = definition.getResolvedFactoryMethod();
        boolean listener = AnnotatedElementUtils.hasAnnotation(type, PaperEventListener.class)
                || factoryMethod != null && AnnotatedElementUtils.hasAnnotation(factoryMethod, PaperEventListener.class);
        if (lifecycle || listener
                || !AnnotatedElementUtils.getMergedRepeatableAnnotations(type, PaperCommandExecutor.class).isEmpty()) candidates.put(beanName, type);
    }

    @Override public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof FactoryBean<?>)) {
            Class<?> type = AopUtils.getTargetClass(bean);
            if (adapter(type)) candidates.put(beanName, type);
        }
        return bean;
    }

    /** Resolve active products after refresh, without eagerly probing unrelated FactoryBeans. */
    Map<String, Class<?>> candidates(ConfigurableListableBeanFactory beans) {
        Map<String, Class<?>> resolved = new LinkedHashMap<>();
        var names = Stream.concat(Arrays.stream(beans.getBeanDefinitionNames()),
                Arrays.stream(beans.getSingletonNames())).distinct().sorted().toList();
        for (String name : names) {
            Class<?> predicted = beans.getType(name, false);
            boolean listener = beans.findAnnotationOnBean(name, PaperEventListener.class, false) != null;
            if (!listener && !candidates.containsKey(name) && !adapter(predicted)) continue;
            if (beans.containsBeanDefinition(name) && beans.getBeanDefinition(name).isLazyInit()
                    || !beans.isSingleton(name)) {
                throw new IllegalArgumentException(name + ": Paper adapters and lifecycle hooks must be eager singletons; FactoryBean products must also be singletons");
            }
            Object bean = beans.getBean(name);
            Class<?> type = AopUtils.getTargetClass(bean);
            if (bean instanceof FactoryBean<?>) continue;
            resolved.put(name, type);
        }
        return Map.copyOf(resolved);
    }

    private static boolean adapter(Class<?> type) {
        return type != null && !FactoryBean.class.isAssignableFrom(type)
                && (AnnotatedElementUtils.hasAnnotation(type, PaperEventListener.class)
                || !AnnotatedElementUtils.getMergedRepeatableAnnotations(type, PaperCommandExecutor.class).isEmpty()
                || Arrays.stream(ReflectionUtils.getAllDeclaredMethods(type)).anyMatch(PaperLifecycleAnnotations::hook));
    }
}
