package org.blu3fishez.springpaper.velocity.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.blu3fishez.springpaper.velocity.bootstrap.VelocityHostConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

/**
 * Ordinary Spring root for one native Velocity plugin. Gradle owns the native descriptor and bridge;
 * the root is constructed only by Spring, never by Guice. Version comes from the Gradle project.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class, excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = com.velocitypowered.api.plugin.Plugin.class) })
@ConfigurationPropertiesScan
@Import(VelocityHostConfiguration.class)
public @interface SpringVelocityApplication {
    String id();
    String name();
    String description() default "";
    String url() default "";
    String[] authors() default {};

    @AliasFor(annotation = ComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] scanBasePackageClasses() default {};

    @AliasFor(annotation = ConfigurationPropertiesScan.class, attribute = "basePackageClasses")
    Class<?>[] configurationPropertiesScanBasePackageClasses() default {};
}
