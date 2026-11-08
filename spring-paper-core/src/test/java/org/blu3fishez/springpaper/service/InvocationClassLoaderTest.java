package org.blu3fishez.springpaper.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.blu3fishez.springpaper.spi.PaperInvocation;
import org.junit.jupiter.api.Test;

class InvocationClassLoaderTest {
    @Test void usesPluginContextLoaderAndRestoresCallerEvenAfterFailure() throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader plugin = new ClassLoader(previous) {};
        var invocations = new PaperInvocationService(List.of(), plugin);
        var input = new PaperInvocation(null, "test", false, PaperInvocation.Kind.EVENT);
        assertSame(plugin, invocations.invoke(input, () -> Thread.currentThread().getContextClassLoader()));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
        assertThrows(IllegalStateException.class, () -> invocations.invoke(input, () -> { throw new IllegalStateException("failure"); }));
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }
}
