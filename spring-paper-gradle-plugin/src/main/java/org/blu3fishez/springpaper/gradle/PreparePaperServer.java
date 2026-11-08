package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Properties;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.DisableCachingByDefault;

/** Developer-only files. EULA acceptance must be explicitly configured by the user. */
@DisableCachingByDefault(because = "Merges an existing, mutable developer server configuration")
public abstract class PreparePaperServer extends DefaultTask {
    @Input public abstract Property<Boolean> getAcceptEula();
    @Input public abstract MapProperty<String, String> getServerProperties();
    @OutputFile public abstract RegularFileProperty getServerPropertiesFile();
    @OutputFile public abstract RegularFileProperty getEulaFile();

    @TaskAction public void prepare() throws IOException {
        var target = getServerPropertiesFile().get().getAsFile().toPath();
        Files.createDirectories(target.getParent());
        Properties properties = new Properties();
        if (Files.exists(target)) try (var input = Files.newInputStream(target)) { properties.load(input); }
        properties.putAll(getServerProperties().get());
        try (var writer = Files.newBufferedWriter(target)) { properties.store(writer, "Spring Paper development server"); }
        var eula = getEulaFile().get().getAsFile().toPath();
        Files.createDirectories(eula.getParent());
        Files.writeString(eula, "# https://aka.ms/MinecraftEULA\neula=" + getAcceptEula().get() + "\n");
    }
}
