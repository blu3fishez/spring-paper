package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs an owner's cleanup on the Paper main thread after native inputs are detached and before
 * queued managed main work is settled, but before workers are drained and Spring infrastructure is
 * destroyed. Final saves must be DB-only; next-tick dispatch is already stopped. Inline main cleanup
 * still runs on this thread. Also cleans a partially failed startup.
 * This callback owns the bean's destruction; cleanup must be idempotent and partial-start safe.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OnPaperMainThreadShutdown {}
