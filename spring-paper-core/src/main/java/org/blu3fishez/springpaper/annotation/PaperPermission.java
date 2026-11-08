package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.bukkit.permissions.PermissionDefault;

/** Additional plugin permission, including nodes checked inside commands rather than on a command label. */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface PaperPermission {
    String name();
    String description() default "";
    PermissionDefault defaultValue() default PermissionDefault.OP;
}
