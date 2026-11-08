package org.blu3fishez.springpaper.spi;

/** Optional input interception implemented by an integration (for example method security). */
public interface PaperInvocationInterceptor {
    Object invoke(PaperInvocation invocation, Invocation next) throws Exception;

    @FunctionalInterface
    interface Invocation { Object call() throws Exception; }
}
