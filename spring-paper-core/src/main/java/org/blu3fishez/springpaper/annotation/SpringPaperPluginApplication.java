package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.bukkit.plugin.PluginLoadOrder;

/** Bootstrap metadata on the Paper-created plugin; it is never constructed by Spring. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpringPaperPluginApplication {
    String name();
    String description() default "";
    String[] authors() default {};
    String website() default "";
    String apiVersion() default "";
    PluginLoadOrder load() default PluginLoadOrder.POSTWORLD;
    String[] depend() default {};
    String[] softDepend() default {};
    String configuration() default "application.yml";
    Class<?>[] configurations() default {};
    Class<?>[] scanBasePackageClasses() default {};
}
