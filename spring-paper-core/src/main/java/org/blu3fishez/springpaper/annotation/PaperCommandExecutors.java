package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.*;
import org.springframework.stereotype.Component;

/** Repeatable command metadata for one executor with distinct usage/permission contracts per label. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface PaperCommandExecutors {
    PaperCommandExecutor[] value();
}
