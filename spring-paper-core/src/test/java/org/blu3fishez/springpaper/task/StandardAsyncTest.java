package org.blu3fishez.springpaper.task;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;

class StandardAsyncTest {
    @Configuration(proxyBeanMethods = false) @EnableAsync
    static class Consumer {
        @Bean Work work() { return new Work(); }
    }
    public static class Work {
        @Async("paperTasks")
        public CompletableFuture<Thread> externallyInvoked() {
            return CompletableFuture.completedFuture(Thread.currentThread());
        }
        public CompletableFuture<Thread> selfInvoked() { return externallyInvoked(); }
    }
    @Test void standardAsyncReusesTheNamedWorkerButSelfInvocationStillBypassesTheProxy() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("paperTasks", PaperTasks.class,
                    () -> new PaperTasks(plugin, new PaperConfiguration(new StandardEnvironment())));
            context.register(Consumer.class);
            context.refresh();
            var workers = context.getBean("paperTasks", AsyncTaskExecutor.class);
            var consumer = context.getBean(Work.class);
            Thread async = consumer.externallyInvoked().get(5, TimeUnit.SECONDS);
            assertNotSame(Thread.currentThread(), async);
            assertTrue(async.isVirtual());
            assertSame(Thread.currentThread(), consumer.selfInvoked().get(5, TimeUnit.SECONDS));
            assertSame(plugin.getClass().getClassLoader(), workers.submitCompletable(
                    () -> Thread.currentThread().getContextClassLoader()).get(5, TimeUnit.SECONDS));
        }
    }
}
