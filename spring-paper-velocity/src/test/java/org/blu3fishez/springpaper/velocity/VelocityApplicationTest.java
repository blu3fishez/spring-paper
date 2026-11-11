package org.blu3fishez.springpaper.velocity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.blu3fishez.springpaper.velocity.bootstrap.VelocityHostConfiguration;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class VelocityApplicationTest {
    @Test void nativeHostObjectsAndInitializerUseOneContextWithoutOwningHostDestruction() {
        var host = new VelocityHost();
        var context = new AtomicReference<ConfigurableApplicationContext>();
        var closed = new AtomicInteger();
        ClassLoader callerLoader = new ClassLoader() {};
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(callerLoader);
        try {
            var application = host.application(HostConfiguration.class, captured -> {
                context.set(captured);
                assertSame(host.plugin.getClass().getClassLoader(), thread.getContextClassLoader());
                assertSame(thread.getContextClassLoader(), captured.getBeanFactory().getBeanClassLoader());
                assertSame(host.proxy, captured.getBeanFactory().getSingleton(VelocityHostConfiguration.PROXY_BEAN_NAME));
                captured.getBeanFactory().registerSingleton("closeObserver", (DisposableBean) closed::incrementAndGet);
                ((org.springframework.context.annotation.AnnotationConfigApplicationContext) captured)
                        .registerBean("managedResource", ManagedResource.class, () -> new ManagedResource(closed));
            });
            VelocityHost.execute(application.initialize());
            assertTrue(application.isReady());
            assertSame(callerLoader, thread.getContextClassLoader());
            assertSame(host.proxy, context.get().getBean(ProxyServer.class));
            assertSame(host.logger, context.get().getBean(Logger.class));
            assertSame(host.directory, context.get().getBean(VelocityHostConfiguration.DATA_DIRECTORY_BEAN_NAME));
            assertSame(host.plugin, context.get().getBean(VelocityHostConfiguration.PLUGIN_BEAN_NAME));
            VelocityHost.execute(application.initialize());
            var shutdown = application.shutdown();
            assertFalse(application.isReady());
            VelocityHost.execute(shutdown);
            VelocityHost.execute(application.shutdown());
            assertFalse(context.get().isActive());
            assertEquals(1, closed.get());
            assertEquals(0, host.plugin.closes.get());
            assertSame(callerLoader, thread.getContextClassLoader());
            verifyNoInteractions(host.events, host.commands);
        } finally { thread.setContextClassLoader(original); }
    }

    @Test void failedInitializerRestoresCallerLoaderAndNeverOpensInputs() {
        var host = new VelocityHost();
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        var expected = new IllegalArgumentException("invalid consumer configuration");
        var application = host.application(HostConfiguration.class, context -> { throw expected; });
        assertSame(expected, assertThrows(IllegalArgumentException.class, () -> VelocityHost.execute(application.initialize())));
        assertSame(original, Thread.currentThread().getContextClassLoader());
        assertFalse(application.isReady());
        VelocityHost.execute(application.shutdown());
        verifyNoInteractions(host.events, host.commands);
    }

    @Test void hostNamesCannotBeReplacedByConsumerDefinitions() {
        var host = new VelocityHost();
        var application = host.application(ConflictingConfiguration.class);
        assertThrows(RuntimeException.class, () -> VelocityHost.execute(application.initialize()));
        assertFalse(application.isReady());
        VelocityHost.execute(application.shutdown());
    }

    @Configuration(proxyBeanMethods = false)
    @Import(VelocityHostConfiguration.class)
    static class HostConfiguration {
        @Bean Object verifiesTypedHost(ProxyServer proxy, Logger logger) { return new Object(); }
    }
    @Configuration(proxyBeanMethods = false)
    @Import(VelocityHostConfiguration.class)
    static class ConflictingConfiguration {
        @Bean("velocityProxyServer") ProxyServer conflictingHost() { return mock(ProxyServer.class); }
    }
    static class ManagedResource implements DisposableBean {
        final AtomicInteger closed;
        ManagedResource(AtomicInteger closed) { this.closed = closed; }
        @Override public void destroy() { closed.incrementAndGet(); }
    }
}
