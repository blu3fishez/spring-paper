package org.blu3fishez.springpaper.gradle;

import com.github.jengelman.gradle.plugins.shadow.ShadowPlugin;
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.blu3fishez.springpaper.config.YamlDocument;
import org.blu3fishez.springpaper.config.PaperConfiguration;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import xyz.jpenilla.runpaper.RunPaperPlugin;
import xyz.jpenilla.runpaper.task.RunServer;

public final class SpringPaperPlugin implements Plugin<Project> {
    @Override public void apply(Project project) {
        project.getPluginManager().apply("java-library");
        SpringPaperExtension extension = project.getExtensions().create("springPaper", SpringPaperExtension.class);
        extension.getConfigurationFile().convention(project.getLayout().getProjectDirectory().file(
                project.getProviders().gradleProperty("springPaper.configuration").getOrElse("src/main/resources/application.yml")));
        var yamlText = project.getProviders().fileContents(extension.getConfigurationFile()).getAsText();
        project.getExtensions().getByType(JavaPluginExtension.class).getToolchain().getLanguageVersion().set(
                yamlText.map(text -> JavaLanguageVersion.of(PaperConfiguration.of(YamlDocument.read(new ByteArrayInputStream(
                        text.getBytes(StandardCharsets.UTF_8)))).integer("spring.paper.minecraft.java-version", 25))));
        project.getPluginManager().apply(ShadowPlugin.class);
        project.getPluginManager().apply(RunPaperPlugin.class);
        var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        var main = sourceSets.getByName("main");
        var descriptor = project.getTasks().register("generatePaperPluginDescriptor", GeneratePluginDescriptor.class, task -> {
            task.getClasses().from(main.getOutput().getClassesDirs());
            task.getLibraries().from(project.getConfigurations().getByName("runtimeClasspath"));
            task.getConfigurationFile().set(extension.getConfigurationFile());
            task.getPluginVersion().set(project.getVersion().toString());
            task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("generated/spring-paper"));
            task.dependsOn(project.getTasks().named("compileJava"));
        });
        project.getTasks().withType(Jar.class).configureEach(task -> task.from(descriptor.flatMap(GeneratePluginDescriptor::getOutputDirectory)));
        project.getTasks().named("jar", Jar.class).configure(task -> task.getArchiveClassifier().set("plain"));
        project.getTasks().named("shadowJar", ShadowJar.class).configure(task -> {
            task.getArchiveClassifier().set("");
            task.mergeServiceFiles();
            task.filesMatching("META-INF/services/**", details -> details.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE));
            task.filesMatching("META-INF/*.kotlin_module", details -> details.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE));
            task.exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST");
            task.dependencies(filter -> filter.exclude(filter.dependency("org.slf4j:.*:.*")));
        });
        project.getTasks().named("assemble").configure(task -> task.dependsOn("shadowJar"));
        project.getRepositories().mavenCentral();
        project.getRepositories().maven(repository -> repository.setUrl("https://repo.papermc.io/repository/maven-public/"));
        project.getDependencies().add("implementation", "org.blu3fishez.springpaper:spring-paper-core:" + version());
        project.afterEvaluate(ignored -> {
            String content = project.getProviders().fileContents(extension.getConfigurationFile()).getAsText().get();
            PaperConfiguration config = PaperConfiguration.of(YamlDocument.read(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
            String minecraft = config.required("spring.paper.minecraft.version");
            String api = config.text("spring.paper.minecraft.api-coordinate", minecraft + ".build.+");
            project.getDependencies().add("compileOnly", "io.papermc.paper:paper-api:" + api);
            int javaVersion = config.integer("spring.paper.minecraft.java-version", 25);
            var runDirectory = project.getLayout().getProjectDirectory().dir(config.text("spring.paper.development.directory", "run"));
            var prepare = project.getTasks().register("preparePaperServer", PreparePaperServer.class, task -> {
                task.getAcceptEula().set(config.enabled("spring.paper.development.accept-eula"));
                task.getServerProperties().set(config.children("spring.paper.development.server-properties"));
                task.getServerPropertiesFile().set(runDirectory.file("server.properties"));
                task.getEulaFile().set(runDirectory.file("eula.txt"));
            });
            var toolchains = project.getExtensions().getByType(JavaToolchainService.class);
            project.getTasks().named("runServer", RunServer.class).configure(task -> {
                task.dependsOn(prepare);
                task.minecraftVersion(minecraft);
                task.getRunDirectory().set(runDirectory);
                task.getJavaLauncher().set(toolchains.launcherFor(spec -> spec.getLanguageVersion().set(JavaLanguageVersion.of(javaVersion))));
                if (config.integer("spring.paper.minecraft.build", 0) > 0) task.build(config.integer("spring.paper.minecraft.build", 0));
                task.jvmArgs(config.strings("spring.paper.development.jvm-args"));
            });
        });
    }
    private static String version() {
        Properties properties = new Properties();
        try (var input = SpringPaperPlugin.class.getResourceAsStream("/spring-paper-version.properties")) {
            if (input == null) throw new IllegalStateException("Missing Spring Paper version metadata");
            properties.load(input);
            return properties.getProperty("version");
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
