package org.blu3fishez.springpaper.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyReloadEvent;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.blu3fishez.springpaper.velocity.annotation.VelocityCommand;
import org.blu3fishez.springpaper.velocity.annotation.VelocityEventListener;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;

class VelocityInputTest {
    @Test void activeBeansRegisterAfterSpringStartAndUnregisterBeforeSpringStop() {
        var host = new VelocityHost();
        var context = new AtomicReference<ConfigurableApplicationContext>();
        var application = host.application(InputConfiguration.class, captured -> {
            context.set(captured);
            captured.getBeanFactory().registerSingleton("fixture", host);
        });
        VelocityHost.execute(application.initialize());
        assertTrue(application.isReady());
        assertEquals(1, host.listeners.size());
        assertSame(context.get().getBean(NativeListener.class), host.listeners.getFirst());
        var meta = host.commandMetas.get("velocity-test");
        assertSame(meta, host.commandMetas.get("vt"));
        assertSame(host.plugin, meta.getPlugin());
        assertEquals("business.start", host.effects.getFirst());
        var shutdown = application.shutdown();
        assertTrue(host.listeners.isEmpty());
        assertTrue(host.commandMetas.isEmpty());
        assertFalse(application.isReady());
        assertFalse(host.effects.contains("business.stop"));
        VelocityHost.execute(shutdown);
        assertEquals("business.stop", host.effects.getLast());
        verify(host.commands).unregister(same(meta));
        verify(host.events, never()).unregisterListeners(any());
    }

    @Test void partialNativeFailureCleansAttemptedRegistrationsAndSpringResources() {
        var host = new VelocityHost();
        var closed = new AtomicInteger();
        doAnswer(call -> {
            host.listeners.add(call.getArgument(1));
            throw new IllegalStateException("native listener registration failed after mutation");
        }).when(host.events).register(same(host.plugin), any());
        var application = host.application(InputConfiguration.class, context -> {
            context.getBeanFactory().registerSingleton("fixture", host);
            ((org.springframework.context.annotation.AnnotationConfigApplicationContext) context)
                    .registerBean("managedResource", VelocityApplicationTest.ManagedResource.class,
                            () -> new VelocityApplicationTest.ManagedResource(closed));
        });
        assertThrows(IllegalStateException.class, () -> VelocityHost.execute(application.initialize()));
        assertFalse(application.isReady());
        assertTrue(host.listeners.isEmpty());
        assertTrue(host.commandMetas.isEmpty());
        assertEquals(1, closed.get());
        VelocityHost.execute(application.shutdown());
    }

    @Test void inputCleanupFailureDoesNotPreventOtherInputsOrContextFromClosing() {
        var host = new VelocityHost();
        var closed = new AtomicInteger();
        var application = host.application(InputConfiguration.class, context -> {
            context.getBeanFactory().registerSingleton("fixture", host);
            ((org.springframework.context.annotation.AnnotationConfigApplicationContext) context)
                    .registerBean("managedResource", VelocityApplicationTest.ManagedResource.class,
                            () -> new VelocityApplicationTest.ManagedResource(closed));
        });
        VelocityHost.execute(application.initialize());
        var expected = new IllegalStateException("native command cleanup unavailable");
        doThrow(expected).when(host.commands).unregister(any(CommandMeta.class));
        EventTask shutdown = assertDoesNotThrow(application::shutdown);
        assertTrue(host.listeners.isEmpty());
        assertFalse(application.isReady());
        assertSame(expected, assertThrows(IllegalStateException.class, () -> VelocityHost.execute(shutdown)));
        assertEquals(1, closed.get());
        VelocityHost.execute(application.shutdown());
    }

    @Test void commandConflictFailsBeforeAcquisitionAndShutdownPreservesNewOwner() {
        var host = new VelocityHost();
        CommandMeta preexisting = mock(CommandMeta.class);
        host.commandMetas.put("vt", preexisting);
        var conflictingApplication = host.application(InputConfiguration.class,
                context -> context.getBeanFactory().registerSingleton("fixture", host));
        assertThrows(IllegalArgumentException.class, () -> VelocityHost.execute(conflictingApplication.initialize()));
        assertSame(preexisting, host.commandMetas.get("vt"));
        verify(host.commands, never()).register(any(CommandMeta.class), any());
        verify(host.events, never()).register(any(), any());
        VelocityHost.execute(conflictingApplication.shutdown());

        host.commandMetas.clear();
        var application = host.application(InputConfiguration.class,
                context -> context.getBeanFactory().registerSingleton("fixture", host));
        VelocityHost.execute(application.initialize());
        CommandMeta ours = host.commandMetas.get("velocity-test");
        CommandMeta replacement = mock(CommandMeta.class);
        host.commandMetas.put("velocity-test", replacement);
        VelocityHost.execute(application.shutdown());
        assertSame(replacement, host.commandMetas.get("velocity-test"));
        assertFalse(host.commandMetas.containsKey("vt"));
        verify(host.commands).unregister(same(ours));
        verify(host.commands, never()).unregister(anyString());
    }

    @Test void classProxyStaysTheRegisteredListenerAndNativeInvocationTraversesAdvice() throws Exception {
        var host = new VelocityHost();
        var advice = new AtomicInteger();
        var target = new NativeListener();
        var factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> {
            advice.incrementAndGet();
            return invocation.proceed();
        });
        Object proxy = factory.getProxy();
        var application = host.application(ProxyConfiguration.class, context ->
                context.getBeanFactory().registerSingleton("proxiedListener", proxy));
        VelocityHost.execute(application.initialize());
        assertSame(proxy, host.listeners.getFirst());
        // Velocity reads @Subscribe on the class hierarchy, then invokes that Method on the same
        // registered object. Keep the real Spring class proxy in that call rather than its raw target.
        Method subscribed = NativeListener.class.getDeclaredMethod("reload", ProxyReloadEvent.class);
        assertEquals(42, subscribed.getAnnotation(Subscribe.class).priority());
        assertFalse(subscribed.getAnnotation(Subscribe.class).async());
        EventTask task = (EventTask) subscribed.invoke(host.listeners.getFirst(), new ProxyReloadEvent());
        VelocityHost.execute(task);
        assertEquals(1, advice.get());
        assertEquals(1, target.calls.get());
        VelocityHost.execute(application.shutdown());
        verify(host.events).unregisterListener(same(host.plugin), same(proxy));
    }

    @Test void inactiveConfigurationDoesNotInstantiateOrRegisterInput() {
        var host = new VelocityHost();
        var application = host.application(ConditionalConfiguration.class, context ->
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", java.util.Map.of("input.enabled", false))));
        VelocityHost.execute(application.initialize());
        assertTrue(application.isReady());
        assertTrue(host.listeners.isEmpty());
        VelocityHost.execute(application.shutdown());
    }

    @Test void jdkListenerProxyFailsInsteadOfSilentlyLosingNativeSubscriptions() {
        var host = new VelocityHost();
        var factory = new ProxyFactory(new NativeListener());
        factory.setInterfaces(ReloadReceiver.class);
        var application = host.application(JdkProxyConfiguration.class,
                context -> context.getBeanFactory().registerSingleton("proxyFixture", factory.getProxy()));
        var failure = assertThrows(IllegalArgumentException.class, () -> VelocityHost.execute(application.initialize()));
        assertTrue(failure.getMessage().contains("JDK proxies hide"));
        assertFalse(application.isReady());
        verifyNoInteractions(host.events, host.commands);
        VelocityHost.execute(application.shutdown());
    }

    @Test void manuallyRegisteredJdkProxyUsesTargetMetadataToFailClearlyWithoutUnwrapping() {
        var host = new VelocityHost();
        var factory = new ProxyFactory(new NativeListener());
        factory.setInterfaces(ReloadReceiver.class);
        var application = host.application(ProxyConfiguration.class,
                context -> context.getBeanFactory().registerSingleton("manualProxy", factory.getProxy()));
        var failure = assertThrows(IllegalArgumentException.class, () -> VelocityHost.execute(application.initialize()));
        assertTrue(failure.getMessage().contains("JDK proxies hide"));
        assertFalse(application.isReady());
        verifyNoInteractions(host.events, host.commands);
        VelocityHost.execute(application.shutdown());
    }

    @Test void singletonFactoryCannotRegisterNonSingletonListenerProducts() {
        var host = new VelocityHost();
        var application = host.application(FactoryConfiguration.class);
        var failure = assertThrows(IllegalArgumentException.class, () -> VelocityHost.execute(application.initialize()));
        assertTrue(failure.getMessage().contains("FactoryBean products must also be singletons"));
        assertFalse(application.isReady());
        verifyNoInteractions(host.events, host.commands);
        VelocityHost.execute(application.shutdown());
    }

    @Test void lazyInputIsRejectedBeforeAnyNativeRegistration() {
        var host = new VelocityHost();
        var application = host.application(LazyConfiguration.class);
        assertThrows(IllegalArgumentException.class, () -> VelocityHost.execute(application.initialize()));
        verifyNoInteractions(host.events, host.commands);
        VelocityHost.execute(application.shutdown());
    }

    @Configuration(proxyBeanMethods = false)
    static class InputConfiguration {
        @Bean @VelocityEventListener NativeListener listener() { return new NativeListener(); }
        @Bean @VelocityCommand(value = "velocity-test", aliases = "vt")
        SimpleCommand command() { return invocation -> {}; }
        @Bean SmartLifecycle business(VelocityHost host) {
            return new SmartLifecycle() {
                boolean running;
                @Override public void start() { assertTrue(host.listeners.isEmpty()); host.effects.add("business.start"); running = true; }
                @Override public void stop() { assertTrue(host.listeners.isEmpty()); host.effects.add("business.stop"); running = false; }
                @Override public boolean isRunning() { return running; }
            };
        }
    }
    @VelocityEventListener
    public static class NativeListener implements ReloadReceiver {
        final AtomicInteger calls = new AtomicInteger();
        @Subscribe(priority = 42, async = false)
        public EventTask reload(ProxyReloadEvent event) { return EventTask.async(calls::incrementAndGet); }
    }
    @Configuration(proxyBeanMethods = false)
    static class ProxyConfiguration {}
    public interface ReloadReceiver { EventTask reload(ProxyReloadEvent event); }
    @Configuration(proxyBeanMethods = false)
    static class JdkProxyConfiguration {
        @Bean @VelocityEventListener ReloadReceiver listener(ReloadReceiver proxyFixture) { return proxyFixture; }
    }
    @Configuration(proxyBeanMethods = false)
    static class ConditionalConfiguration {
        @Bean @VelocityEventListener @Conditional(Enabled.class)
        NativeListener inactive() { throw new AssertionError("inactive input must not be constructed"); }
    }
    static class Enabled implements Condition {
        @Override public boolean matches(ConditionContext context, org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return context.getEnvironment().getProperty("input.enabled", Boolean.class, true);
        }
    }
    @Configuration(proxyBeanMethods = false)
    static class LazyConfiguration {
        @Bean @Lazy @VelocityEventListener NativeListener lazy() { return new NativeListener(); }
    }
    @Configuration(proxyBeanMethods = false)
    static class FactoryConfiguration {
        @Bean NonSingletonListenerFactory listenerFactory() { return new NonSingletonListenerFactory(); }
    }
    static class NonSingletonListenerFactory implements org.springframework.beans.factory.FactoryBean<NativeListener> {
        @Override public NativeListener getObject() { return new NativeListener(); }
        @Override public Class<?> getObjectType() { return NativeListener.class; }
        @Override public boolean isSingleton() { return false; }
    }
}
