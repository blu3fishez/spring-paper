package org.blu3fishez.springpaper.lifecycle;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Metadata uses integer positions in plans; consumer equals/hashCode never participate in ordering. */
record PaperLifecycleHook(String name, Object bean, Method start, Method stop, int phase, int stopPhase) {
    void startOwner() { invoke(start); }
    void stopOwner() { invoke(stop); }
    private void invoke(Method method) {
        if (method == null) return;
        try { method.invoke(bean); }
        catch (InvocationTargetException error) {
            throw new IllegalStateException("Lifecycle hook failed: " + name, error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Lifecycle hook is not invocable: " + name, error);
        }
    }
}
