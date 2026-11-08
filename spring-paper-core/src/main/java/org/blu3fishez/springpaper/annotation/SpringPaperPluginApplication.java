package org.blu3fishez.springpaper.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.bukkit.plugin.PluginLoadOrder;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Bootstrap metadata on the Paper-created plugin; it is never constructed by Spring.
 * 설계 제약: Component/Configuration 합성 어노테이션으로 만들면 스캔 시 JavaPlugin을 재생성하게 된다.
 * Paper가 생성한 기존 인스턴스는 bootstrap이 singleton으로 등록하고, 소비자의 구성 클래스만 별도 등록한다.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpringPaperPluginApplication {
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
    String configuration() default "application.yml";
    Class<?>[] configurations() default {};
    Class<?>[] scanBasePackageClasses() default {};
    /** Run before refresh, on the Paper thread, for existing host configuration and singleton registration. */
    Class<? extends ApplicationContextInitializer<ConfigurableApplicationContext>>[] initializers() default {};
}
