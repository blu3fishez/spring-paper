package org.blu3fishez.springpaper.boot.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Forked development-tool entrypoint. Values are written only to the caller-owned private output file. */
public final class ConfigDataProbe {
    private ConfigDataProbe() {}

    public static void main(String[] args) throws Exception {
        Path artifact = null;
        Path dataDirectory = null;
        Path output = null;
        var applicationArgs = new ArrayList<String>();
        var nativeFields = new ArrayList<String>();
        var nativeMaps = new ArrayList<String>();
        for (int i = 0; i < args.length; i++) {
            String argument = args[i];
            if (argument.equals("--native-field") || argument.equals("--native-map")) {
                if (++i == args.length) throw new IllegalArgumentException("Missing native field path");
                (argument.equals("--native-map") ? nativeMaps : nativeFields).add(args[i]);
            } else if (argument.equals("--jar") || argument.equals("--data-dir") || argument.equals("--output")) {
                if (++i == args.length) throw new IllegalArgumentException("Missing helper argument value");
                Path value = Path.of(args[i]);
                switch (argument) {
                    case "--jar" -> artifact = value;
                    case "--data-dir" -> dataDirectory = value;
                    case "--output" -> output = value;
                    default -> throw new AssertionError(argument);
                }
            } else applicationArgs.add(argument);
        }
        if (artifact == null || dataDirectory == null || output == null) {
            throw new IllegalArgumentException("ConfigDataProbe requires --jar, --data-dir and --output");
        }
        var environment = ConfigDataSettings.load(artifact, dataDirectory,
                Thread.currentThread().getContextClassLoader(), applicationArgs.toArray(String[]::new));
        var options = new DumperOptions();
        options.setAllowUnicode(false);
        options.setNonPrintableStyle(DumperOptions.NonPrintableStyle.ESCAPE);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.DOUBLE_QUOTED);
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            new Yaml(options).dump(ConfigDataSettings.launchSettings(environment, nativeFields, nativeMaps), writer);
        }
    }
}
