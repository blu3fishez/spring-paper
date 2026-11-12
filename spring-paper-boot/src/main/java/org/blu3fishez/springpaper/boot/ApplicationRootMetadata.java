package org.blu3fishez.springpaper.boot;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;

/** Reads only the bridge's own artifact, never another plugin's classpath resource. */
public final class ApplicationRootMetadata {
    public static final String LOCATION = "META-INF/spring-paper/application.properties";
    private ApplicationRootMetadata() {}

    public static Path artifact(Class<?> bridge) {
        try { return Path.of(bridge.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath(); }
        catch (Exception error) { throw new IllegalStateException("Cannot locate native plugin artifact", error); }
    }

    public static Class<?> load(Class<?> bridge, String expectedHost) {
        Path artifact = artifact(bridge);
        Properties metadata = new Properties();
        try {
            if (Files.isDirectory(artifact)) {
                try (var reader = Files.newBufferedReader(artifact.resolve(LOCATION), StandardCharsets.UTF_8)) { metadata.load(reader); }
            } else {
                try (var jar = new JarFile(artifact.toFile())) {
                    var entry = jar.getJarEntry(LOCATION);
                    if (entry == null) throw new IOException("Missing " + LOCATION);
                    try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) { metadata.load(reader); }
                }
            }
            if (!expectedHost.equals(metadata.getProperty("host"))) {
                throw new IllegalArgumentException("Application metadata host must be " + expectedHost);
            }
            String root = metadata.getProperty("root-class", "");
            if (root.isBlank()) throw new IllegalArgumentException("Application metadata requires root-class");
            Class<?> applicationRoot = Class.forName(root, false, bridge.getClassLoader());
            if (applicationRoot.getClassLoader() != bridge.getClassLoader()
                    || !artifact(applicationRoot).equals(artifact)) {
                throw new IllegalArgumentException("Application root must belong to the native plugin artifact");
            }
            return applicationRoot;
        } catch (IOException | ClassNotFoundException error) {
            throw new IllegalStateException("Cannot read native application root metadata", error);
        }
    }
}
