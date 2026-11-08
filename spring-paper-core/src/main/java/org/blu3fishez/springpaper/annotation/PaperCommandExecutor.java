package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/** Commands are canonical labels; aliases and permission metadata are generated into plugin.yml. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Repeatable(PaperCommandExecutors.class)
public @interface PaperCommandExecutor {
    String[] commands();
    String description() default "";
    String usage() default "";
    String[] aliases() default {};
    String permission() default "";
    String permissionDefault() default "op";
}
