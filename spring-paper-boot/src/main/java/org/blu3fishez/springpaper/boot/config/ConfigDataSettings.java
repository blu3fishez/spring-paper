package org.blu3fishez.springpaper.boot.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;

/** The same explicit own-artifact/data-directory locations for runtime and forked launch preparation. */
public final class ConfigDataSettings {
    private ConfigDataSettings() {}

    /** Creates the environment; SpringApplication performs its normal environment post-processing later. */
    public static ConfigurableEnvironment prepare(Path artifact, Path dataDirectory) {
        StandardEnvironment environment = new StandardEnvironment();
        String packaged = artifactLocation(artifact);
        String external = directoryLocation(dataDirectory);
        // Boot location groups keep packaged profiles before external base and external profiles.
        environment.getPropertySources().addLast(new MapPropertySource("nativeConfigDataDefaults", Map.of(
                "spring.config.location", "optional:" + packaged + ",optional:" + external)));
        return environment;
    }

    /** Parses launch settings without creating an application context or changing JVM lifecycle policy. */
    public static ConfigurableEnvironment load(Path artifact, Path dataDirectory, ClassLoader loader, String... args) {
        ConfigurableEnvironment environment = prepare(artifact, dataDirectory);
        if (args.length != 0) environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource(args));
        SpringApplication application = new SpringApplication(new DefaultResourceLoader(loader));
        new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, application);
        ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(loader), null);
        return environment;
    }

    /** Only native launch prefixes cross the helper process seam; unrelated environment values stay private. */
    public static Map<String, Object> launchSettings(ConfigurableEnvironment environment) {
        Binder binder = Binder.get(environment);
        Map<String, Object> settings = new LinkedHashMap<>();
        var typedCollections = new IndexedCollections();
        boolean forwarding = binder.bind("spring.paper.velocity.enabled", Boolean.class).orElse(false);
        settings.put("paper-velocity", forwarding
                ? binder.bind("spring.paper.velocity", Bindable.mapOf(String.class, Object.class), typedCollections)
                        .orElseGet(LinkedHashMap::new)
                : Map.of("enabled", false));
        settings.put("velocity-server", binder.bind("spring.velocity.server", Bindable.mapOf(String.class, Object.class), typedCollections)
                .orElseGet(LinkedHashMap::new));
        return settings;
    }

    private static final class IndexedCollections extends AbstractBindHandler {
        @Override @SuppressWarnings("unchecked")
        public <T> Bindable<T> onStart(ConfigurationPropertyName name, Bindable<T> target, BindContext context) {
            if (target.getType().resolve() == Map.class) {
                for (var source : context.getSources()) {
                    if (source instanceof IterableConfigurationPropertySource iterable) {
                        for (var property : iterable) {
                            if (name.isAncestorOf(property) && property.isNumericIndex(name.getNumberOfElements())) {
                                return (Bindable<T>) Bindable.listOf(Object.class);
                            }
                        }
                    }
                }
            }
            return target;
        }
    }

    private static String artifactLocation(Path artifact) {
        Path absolute = artifact.toAbsolutePath().normalize();
        if (Files.isDirectory(absolute)) return directoryLocation(absolute);
        if (!Files.isRegularFile(absolute)) throw new IllegalArgumentException("Application artifact does not exist");
        return "jar:" + absolute.toUri().toASCIIString() + "!/";
    }

    private static String directoryLocation(Path directory) {
        String location = directory.toAbsolutePath().normalize().toUri().toASCIIString();
        return location.endsWith("/") ? location : location + "/";
    }
}
