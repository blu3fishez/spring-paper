package org.blu3fishez.springpaper.security.connection.internal;

import java.util.function.Supplier;

/** Internal invocation scope, shared by filters, providers and repository/handler calls. */
public final class ContextClassLoaderScope {
    private ContextClassLoaderScope() {}
    public static ClassLoader capture() {
        var loader = Thread.currentThread().getContextClassLoader();
        return loader == null ? ContextClassLoaderScope.class.getClassLoader() : loader;
    }
    public static <T> T call(ClassLoader loader, Supplier<T> action) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try { return action.get(); } finally { thread.setContextClassLoader(previous); }
    }
}
