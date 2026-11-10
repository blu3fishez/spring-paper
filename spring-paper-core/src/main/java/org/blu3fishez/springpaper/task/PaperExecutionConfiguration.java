package org.blu3fishez.springpaper.task;

import java.util.List;
import java.util.concurrent.Executor;
import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.blu3fishez.springpaper.config.internal.DefaultBeans;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.DependencyDescriptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.MethodParameter;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;

/** Imported by core. The worker role is explicit so a main/timer executor cannot be picked accidentally. */
@Configuration(proxyBeanMethods = false)
public class PaperExecutionConfiguration {
    public static final String DEFAULT_WORKER_BEAN_NAME = "paperDefaultWorkerExecutor";

    @Bean(name = DEFAULT_WORKER_BEAN_NAME, defaultCandidate = false) @Lazy @Fallback
    @Qualifier(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME)
    public SimpleAsyncTaskExecutor paperDefaultWorkerExecutor(PaperConfiguration configuration,
            ObjectProvider<TaskDecorator> decorators) {
        var executor = standaloneWorker(configuration);
        List<TaskDecorator> selected = decorators.orderedStream().toList();
        if (!selected.isEmpty()) executor.setTaskDecorator(new CompositeTaskDecorator(selected));
        return executor;
    }
    static SimpleAsyncTaskExecutor standaloneWorker(PaperConfiguration configuration) {
        int seconds = configuration.integer("spring.paper.lifecycle.shutdown-timeout-seconds", 5);
        if (seconds < 0) throw new IllegalArgumentException("Shutdown timeout must be nonnegative");
        var executor = new SimpleAsyncTaskExecutor("spring-paper-worker-");
        executor.setVirtualThreads(true);
        // Spring 7 waits first, then interrupts unfinished tasks (cancelRemainingTasksOnClose=false).
        // A zero configured budget still needs Spring's tracking enabled before immediate interruption.
        executor.setTaskTerminationTimeout(seconds == 0 ? 1 : seconds * 1000L);
        if (seconds == 0) executor.setCancelRemainingTasksOnClose(true);
        return executor;
    }
    @Bean(PaperCoreConfiguration.WORKERS_BEAN_NAME) @Fallback
    public PaperTasks paperTasks(JavaPlugin plugin, PaperConfiguration configuration,
            @Qualifier(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME) Executor worker,
            ConfigurableListableBeanFactory factory) {
        AutoCloseable shutdown = factory.getSingleton(DEFAULT_WORKER_BEAN_NAME) == worker
                ? ((SimpleAsyncTaskExecutor) worker)::close : null;
        return new PaperTasks(plugin, configuration, worker, shutdown);
    }
    @Bean public static BeanFactoryPostProcessor paperWorkerDefaults() {
        return factory -> {
            try {
                var method = PaperExecutionConfiguration.class.getMethod("paperTasks", JavaPlugin.class,
                        PaperConfiguration.class, Executor.class, ConfigurableListableBeanFactory.class);
                DefaultBeans.backOff(factory, new DependencyDescriptor(new MethodParameter(method, 2), true, false),
                        DEFAULT_WORKER_BEAN_NAME);
            } catch (NoSuchMethodException impossible) { throw new IllegalStateException(impossible); }
        };
    }
}
