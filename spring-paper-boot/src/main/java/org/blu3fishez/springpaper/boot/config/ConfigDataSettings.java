package org.blu3fishez.springpaper.boot.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindException;
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
        NativeJsonValues.restore(environment);
        return environment;
    }

    /** Only native launch prefixes cross the helper process seam; unrelated environment values stay private. */
    public static Map<String, Object> launchSettings(ConfigurableEnvironment environment) {
        return launchSettings(environment, java.util.List.of());
    }

    /** Projects the launcher's fixed scalar fields through Binder before native schema conversion. */
    public static Map<String, Object> launchSettings(ConfigurableEnvironment environment, Collection<String> nativeFields) {
        return launchSettings(environment, nativeFields, java.util.List.of());
    }

    /** Known container paths retain Boot's aggregate/list selection as well as their canonical group name. */
    public static Map<String, Object> launchSettings(ConfigurableEnvironment environment, Collection<String> nativeFields,
            Collection<String> nativeMaps) {
        Binder binder = Binder.get(environment);
        Map<String, Object> settings = new LinkedHashMap<>();
        var typedCollections = new IndexedCollections();
        boolean forwarding = forwarding(binder, nativeFields);
        settings.put("paper-velocity", forwarding
                ? binder.bind("spring.paper.velocity", Bindable.mapOf(String.class, Object.class), typedCollections)
                        .orElseGet(LinkedHashMap::new)
                : Map.of("enabled", false));
        settings.put("velocity-server", binder.bind("spring.velocity.server", Bindable.mapOf(String.class, Object.class), typedCollections)
                .orElseGet(LinkedHashMap::new));
        if (!nativeFields.isEmpty()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String field : nativeFields) {
                boolean paper = paperField(field);
                if (paper && !forwarding && !field.equals("spring.paper.velocity.enabled")) continue;
                binder.bind(field, Object.class).ifBound(value -> values.put(field, value));
            }
            settings.put("native-field-values", values);
        }
        if (!nativeMaps.isEmpty()) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (String field : nativeMaps) {
                if (paperField(field) && !forwarding) continue;
                binder.bind(field, Bindable.mapOf(String.class, Object.class), typedCollections)
                        .ifBound(value -> values.put(field, value));
            }
            settings.put("native-map-values", values);
        }
        return settings;
    }

    private static boolean forwarding(Binder binder, Collection<String> nativeFields) {
        String enabled = "spring.paper.velocity.enabled";
        if (!nativeFields.contains(enabled)) return binder.bind(enabled, Boolean.class).orElse(false);
        Object value = binder.bind(enabled, Object.class).orElse(false);
        if (!(value instanceof Boolean || value instanceof String)) return false;
        try { return binder.bind(enabled, Boolean.class).orElse(false); }
        catch (BindException invalid) {
            // The launcher's known-field schema rejects this value without reading unused secrets.
            return false;
        }
    }

    private static boolean paperField(String field) {
        boolean paper = field.startsWith("spring.paper.velocity.");
        if ((!paper && !field.startsWith("spring.velocity.server.")) || field.contains("[") || field.contains("]")) {
            throw new IllegalArgumentException("Native field paths must belong to the two launch prefixes");
        }
        ConfigurationPropertyName.of(field); // The launcher supplies canonical fixed field paths.
        return paper;
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
