package org.blu3fishez.springpaper.service;

import java.util.List;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.blu3fishez.springpaper.spi.PaperInvocationInterceptor;

public final class PaperInvocationService {
    private final List<PaperInvocationInterceptor> interceptors;
    private final ClassLoader classLoader;
    public PaperInvocationService(List<PaperInvocationInterceptor> interceptors) {
        this(interceptors, null);
    }
    public PaperInvocationService(List<PaperInvocationInterceptor> interceptors, ClassLoader classLoader) {
        this.interceptors = List.copyOf(interceptors);
        this.classLoader = classLoader;
    }
    public Object invoke(PaperInvocation input, PaperInvocationInterceptor.Invocation action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        if (classLoader != null) thread.setContextClassLoader(classLoader);
        try { return invoke(0, input, action); }
        finally { if (classLoader != null) thread.setContextClassLoader(previous); }
    }
    private Object invoke(int index, PaperInvocation input, PaperInvocationInterceptor.Invocation action) throws Exception {
        if (index == interceptors.size()) return action.call();
        return interceptors.get(index).invoke(input, () -> invoke(index + 1, input, action));
    }
}
