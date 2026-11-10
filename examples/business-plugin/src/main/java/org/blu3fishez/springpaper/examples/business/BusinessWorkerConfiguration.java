package org.blu3fishez.springpaper.examples.business;

import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;

/** Entirely optional: omit this import to use the library's virtual-per-task default. */
@Configuration(proxyBeanMethods = false)
public class BusinessWorkerConfiguration {
    @Bean TaskDecorator requestLocale() {
        return action -> {
            var locale = LocaleContextHolder.getLocaleContext();
            return () -> {
                var previous = LocaleContextHolder.getLocaleContext();
                try { LocaleContextHolder.setLocaleContext(locale); action.run(); }
                finally { LocaleContextHolder.setLocaleContext(previous); }
            };
        };
    }
    @Bean(destroyMethod = "close") @Qualifier(PaperCoreConfiguration.WORKER_EXECUTOR_BEAN_NAME)
    SimpleAsyncTaskExecutor businessWorker(TaskDecorator requestLocale) {
        var executor = new SimpleAsyncTaskExecutor("inbox-worker-");
        executor.setVirtualThreads(true);
        executor.setTaskTerminationTimeout(5000);
        executor.setTaskDecorator(requestLocale);
        return executor; // Spring owns this bean. PaperTasks borrows it and drains only its own work.
    }
}
