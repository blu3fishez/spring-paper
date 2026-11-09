package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.bukkit.event.Listener;

/**
 * An optional relationship on an EventHandler method. Applies to listeners in the same native
 * HandlerList/priority; it never overrides Paper priority or another plugin's registrations.
 * Referenced listener types absent in the active role impose no dependency.
 * If a referenced listener handles several priorities, its matching priority is selected.
 * With no matching priority, a relationship that reverses native priority is rejected.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PaperEventOrder {
    Class<? extends Listener>[] before() default {};
    Class<? extends Listener>[] after() default {};
}
