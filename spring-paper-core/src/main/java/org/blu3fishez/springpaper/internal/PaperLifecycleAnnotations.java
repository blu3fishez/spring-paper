package org.blu3fishez.springpaper.internal;

import java.lang.reflect.Method;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadShutdown;
import org.blu3fishez.springpaper.annotation.OnPaperMainThreadStartup;
import org.blu3fishez.springpaper.annotation.PaperStart;
import org.blu3fishez.springpaper.annotation.PaperStop;

/** Internal metadata rules shared by discovery, eager validation and invocation planning. */
@SuppressWarnings("deprecation")
public final class PaperLifecycleAnnotations {
    private PaperLifecycleAnnotations() {}
    public static boolean startup(Method method) {
        return method.isAnnotationPresent(OnPaperMainThreadStartup.class) || method.isAnnotationPresent(PaperStart.class);
    }
    public static boolean shutdown(Method method) {
        return method.isAnnotationPresent(OnPaperMainThreadShutdown.class) || method.isAnnotationPresent(PaperStop.class);
    }
    public static boolean hook(Method method) { return startup(method) || shutdown(method); }

    public static void validateExclusive(Method method) {
        if (method.isAnnotationPresent(OnPaperMainThreadStartup.class) && method.isAnnotationPresent(PaperStart.class)
                || method.isAnnotationPresent(OnPaperMainThreadShutdown.class) && method.isAnnotationPresent(PaperStop.class)) {
            throw new IllegalArgumentException(method + ": use one main-thread lifecycle annotation per direction");
        }
    }
}
