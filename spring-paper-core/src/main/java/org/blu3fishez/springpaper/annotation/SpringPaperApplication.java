package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.blu3fishez.springpaper.bootstrap.PaperCoreConfiguration;
import org.bukkit.plugin.PluginLoadOrder;
import org.bukkit.plugin.java.JavaPlugin;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;

/** Ordinary Boot root. The native Paper bridge remains a separate, Paper-owned singleton. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = JavaPlugin.class),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = SpringPaperPluginApplication.class) })
@ConfigurationPropertiesScan
@Import(PaperCoreConfiguration.class)
public @interface SpringPaperApplication {
    String name();
    String prefix() default "";
    String description() default "";
    String[] authors() default {};
    String website() default "";
    String apiVersion() default "";
    PluginLoadOrder load() default PluginLoadOrder.POSTWORLD;
    String[] depend() default {};
    String[] softDepend() default {};
    PaperPermission[] permissions() default {};
    @AliasFor(annotation = ComponentScan.class, attribute = "basePackageClasses")
    Class<?>[] scanBasePackageClasses() default {};
    @AliasFor(annotation = ConfigurationPropertiesScan.class, attribute = "basePackageClasses")
    Class<?>[] configurationPropertiesScanBasePackageClasses() default {};
    /** Runs on the Paper thread after ConfigData and before refresh. */
    Class<? extends ApplicationContextInitializer<ConfigurableApplicationContext>>[] initializers() default {};
}
