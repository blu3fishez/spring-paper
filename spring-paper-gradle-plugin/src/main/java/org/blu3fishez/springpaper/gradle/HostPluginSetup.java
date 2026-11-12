package org.blu3fishez.springpaper.gradle;

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import com.github.jengelman.gradle.plugins.shadow.transformers.PropertiesFileTransformer;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/** The two hosts share a flat packaging implementation, never an executable Boot archive. */
final class HostPluginSetup {
    private HostPluginSetup() {}
    static void apply(Project project, String host) {
        String marker = "org.blu3fishez.springpaper.application-host";
        var extra = project.getExtensions().getExtraProperties();
        if (extra.has(marker)) throw new GradleException("A Gradle project must have exactly one application host; already applied " + extra.get(marker));
        extra.set(marker, host);
        project.getPluginManager().apply("java-library");
        project.getExtensions().getByType(JavaPluginExtension.class).getToolchain().getLanguageVersion().convention(JavaLanguageVersion.of(25));
        project.getPluginManager().apply(ShadowPlugin.class);
        project.getRepositories().mavenCentral();
        project.getRepositories().maven(repository -> repository.setUrl("https://repo.papermc.io/repository/maven-public/"));
        project.getDependencies().add("implementation", "org.blu3fishez.springpaper:spring-paper-" + (host.equals("paper") ? "core" : "velocity") + ":" + version());
    }
    static Provider<Map<String, Object>> legacy(Project project, RegularFileProperty file, String property) {
        file.convention(project.getLayout().getProjectDirectory().file(
                project.getProviders().gradleProperty(property).getOrElse("src/main/resources/application.yml")));
        return project.getProviders().of(LegacyBuildSettings.class, spec -> spec.getParameters().getConfigurationFile().set(file));
    }
    static TaskProvider<ShadowJar> packaging(Project project, String host, RegularFileProperty file, Provider<String> apiVersion) {
        var main = project.getExtensions().getByType(SourceSetContainer.class).getByName("main");
        var descriptor = project.getTasks().register(host.equals("paper") ? "generatePaperPluginDescriptor" : "generateVelocityPluginDescriptor",
                GeneratePluginDescriptor.class, task -> {
                    task.getHost().set(host);
                    task.getClasses().from(main.getOutput().getClassesDirs());
                    task.getLibraries().from(project.getConfigurations().getByName("runtimeClasspath"));
                    task.getConfigurationFile().set(file);
                    if (apiVersion != null) task.getApiVersion().set(apiVersion);
                    task.getPluginVersion().set(project.provider(() -> project.getVersion().toString()));
                    task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("generated/spring-" + host));
                    task.dependsOn(project.getTasks().named("compileJava"));
                });
        project.getTasks().withType(Jar.class).configureEach(task -> task.from(descriptor.flatMap(GeneratePluginDescriptor::getOutputDirectory)));
        project.getTasks().named("jar", Jar.class).configure(task -> task.getArchiveClassifier().set("plain"));
        var shadow = project.getTasks().named("shadowJar", ShadowJar.class);
        shadow.configure(task -> {
            task.getArchiveClassifier().set("");
            task.mergeServiceFiles();
            task.transform(new SpringImportsTransformer(project.getObjects()));
            task.transform(PropertiesFileTransformer.class, transformer -> {
                transformer.getPaths().add("^META-INF/spring\\.factories$");
                transformer.getMergeStrategy().set(PropertiesFileTransformer.MergeStrategy.Append);
            });
            for (String pattern : new String[]{"META-INF/services/**", "META-INF/spring/*.imports", "META-INF/spring.factories", "META-INF/*.kotlin_module"}) {
                task.filesMatching(pattern, details -> details.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE));
            }
            task.exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST");
            task.dependencies(filter -> {
                filter.exclude(filter.dependency("org.slf4j:.*:.*"));
                filter.exclude(filter.dependency("io.papermc.paper:paper-api:.*"));
                filter.exclude(filter.dependency("com.velocitypowered:velocity-api:.*"));
            });
        });
        project.getTasks().named("assemble").configure(task -> task.dependsOn(shadow));
        return shadow;
    }
    static void development(Project project, DevelopmentOptions development, Provider<Map<String, Object>> legacy, String prefix) {
        development.getDirectory().convention(project.getLayout().getProjectDirectory().dir(legacy.map(values ->
                LegacyBuildSettings.configuration(values).text(prefix + ".directory", "run"))));
        development.getAcceptEula().convention(legacy.map(values -> LegacyBuildSettings.configuration(values).enabled(prefix + ".accept-eula")));
        development.getServerProperties().convention(legacy.map(values -> LegacyBuildSettings.configuration(values).children(prefix + ".server-properties")));
        development.getJvmArgs().convention(legacy.map(values -> LegacyBuildSettings.configuration(values).strings(prefix + ".jvm-args")));
    }
    private static String version() {
        Properties properties = new Properties();
        try (var input = HostPluginSetup.class.getResourceAsStream("/spring-paper-version.properties")) {
            if (input == null) throw new IllegalStateException("Missing Spring Paper version metadata");
            properties.load(input);
            return properties.getProperty("version");
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
