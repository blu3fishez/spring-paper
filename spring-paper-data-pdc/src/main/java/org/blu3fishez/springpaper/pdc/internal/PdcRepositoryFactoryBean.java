package org.blu3fishez.springpaper.pdc.internal;

import lombok.RequiredArgsConstructor;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import org.blu3fishez.springpaper.pdc.PdcRepository;
import org.blu3fishez.springpaper.pdc.PdcRepositoryFactory;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.ResolvableType;

/** Standard FactoryBean product: Spring owns singleton caching, post-processing and advice. */
@RequiredArgsConstructor
public final class PdcRepositoryFactoryBean implements FactoryBean<Object>, InitializingBean {
    private final Class<?> repositoryType;
    private final PdcRepositoryFactory factory;
    private Object repository;

    @Override public void afterPropertiesSet() {
        if (!repositoryType.isInterface()) throw new IllegalArgumentException("PDC repository must be an interface");
        ResolvableType contract = ResolvableType.forClass(repositoryType).as(PdcRepository.class);
        Class<?> holder = contract.getGeneric(0).resolve();
        Class<?> model = contract.getGeneric(1).resolve();
        if (holder == null || model == null || model == Object.class) throw new IllegalArgumentException("PDC holder and document types must be concrete: " + repositoryType.getName());
        PdcRepository<?, ?> delegate = holder == Entity.class ? factory.entity(model)
                : holder == ItemStack.class ? factory.item(model)
                : holder == PersistentDataContainer.class ? factory.container(model) : null;
        if (delegate == null) throw new IllegalArgumentException("Unsupported PDC holder: " + holder.getName());
        for (Method method : repositoryType.getMethods()) {
            if (method.isDefault() || java.lang.reflect.Modifier.isStatic(method.getModifiers()) || method.getDeclaringClass() == Object.class) continue;
            baseMethod(method, holder, model); // Reject invented derived queries during refresh, before the first live event.
        }
        repository = Proxy.newProxyInstance(repositoryType.getClassLoader(), new Class<?>[] {repositoryType}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "PdcRepository[" + repositoryType.getName() + "]";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new IllegalStateException(method.toString());
            };
            if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
            try { return baseMethod(method, holder, model).invoke(delegate, args); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    private static Method baseMethod(Method requested, Class<?> holder, Class<?> model) {
        Method base = Arrays.stream(PdcRepository.class.getMethods())
                .filter(method -> method.getName().equals(requested.getName())
                        && method.getParameterCount() == requested.getParameterCount()
                        && method.getReturnType().isAssignableFrom(requested.getReturnType()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Use a default method for custom PDC operations: " + requested));
        Class<?>[] parameters = requested.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            Class<?> expected = i == 0 ? holder : model;
            if (parameters[i] != Object.class && parameters[i] != expected) {
                throw new IllegalArgumentException("Incompatible PDC repository parameter: " + requested);
            }
        }
        return base;
    }
    @Override public Object getObject() { return repository; }
    @Override public Class<?> getObjectType() { return repositoryType; }
    @Override public boolean isSingleton() { return true; }
}
