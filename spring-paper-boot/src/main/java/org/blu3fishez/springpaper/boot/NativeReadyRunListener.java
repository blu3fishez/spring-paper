package org.blu3fishez.springpaper.boot;

import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;

/** Activates native inputs before Boot publishes ready and ACCEPTING_TRAFFIC. */
public final class NativeReadyRunListener implements SpringApplicationRunListener, Ordered {
    private final BootApplication application;

    public NativeReadyRunListener(SpringApplication application, String[] arguments) {
        this.application = application instanceof BootApplication nativeApplication ? nativeApplication : null;
    }

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
        if (application != null) application.activate(context);
    }
}
