package org.blu3fishez.springpaper.bootstrap;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import org.blu3fishez.springpaper.annotation.PaperCommandExecutor;
import org.blu3fishez.springpaper.annotation.PaperEventListener;
import org.blu3fishez.springpaper.annotation.PaperStart;
import org.blu3fishez.springpaper.annotation.PaperStop;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;

/** Discovery only. Paper side effects happen after refresh, on the platform thread. */
public final class PaperBeanCatalog implements MergedBeanDefinitionPostProcessor {
    private final Map<String, Class<?>> candidates = new ConcurrentHashMap<>();

    @Override public void postProcessMergedBeanDefinition(RootBeanDefinition definition, Class<?> type, String beanName) {
        boolean stop = false;
        boolean lifecycle = false;
        for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
            lifecycle |= method.isAnnotationPresent(PaperStart.class) || method.isAnnotationPresent(PaperStop.class);
        }
        for (Method method : type.getMethods()) {
            if (!method.isBridge() && !method.isSynthetic()) stop |= method.isAnnotationPresent(PaperStop.class);
        }
        if (stop) {
            if (DisposableBean.class.isAssignableFrom(type)) throw new IllegalArgumentException(beanName + ": use one shutdown owner, PaperStop or DisposableBean");
            for (Method method : ReflectionUtils.getAllDeclaredMethods(type)) {
                if (Arrays.stream(method.getAnnotations()).anyMatch(a -> a.annotationType().getName().equals("jakarta.annotation.PreDestroy"))) {
                    throw new IllegalArgumentException(beanName + ": use one shutdown owner, PaperStop or PreDestroy");
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
        if (lifecycle || AnnotatedElementUtils.hasAnnotation(type, PaperEventListener.class)
                || AnnotatedElementUtils.hasAnnotation(type, PaperCommandExecutor.class)) candidates.put(beanName, type);
    }

    public Map<String, Class<?>> candidates() { return Map.copyOf(candidates); }
}
