package org.blu3fishez.springpaper.pdc;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.blu3fishez.springpaper.pdc.internal.PdcRepositoriesRegistrar;
import org.blu3fishez.springpaper.pdc.internal.PdcConfiguration;
import org.springframework.context.annotation.Import;

/** Scans only the selected configuration's packages; inactive role configurations stay excluded. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import({PdcConfiguration.class, PdcRepositoriesRegistrar.class})
public @interface EnablePaperPdcRepositories {
    String[] basePackages() default {};
    Class<?>[] basePackageClasses() default {};
}
