package org.blu3fishez.springpaper.gradle;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.gradle.api.GradleException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Converts resolved launch settings into complete tool-owned native configuration files. */
final class NativeConfiguration {
    record Plan(Map<Path, String> files, Path secretSource, String secretVariable, boolean requiresSecret) {}
    private NativeConfiguration() {}
    static Plan paper(Path root, Map<String, Object> velocity, List<String> args, boolean acceptEula, Map<String, String> serverProperties) {
        boolean enabled = bool(velocity.getOrDefault("enabled", false), "spring.paper.velocity.enabled");
        Map<String, String> cli = paperArguments(args);
        Map<String, String> properties = new TreeMap<>(serverProperties);
        if (enabled) {
            if (properties.containsKey("online-mode") && !properties.get("online-mode").equalsIgnoreCase("false")) {
                throw new GradleException("Velocity backend requires server.properties online-mode=false");
            }
            properties.put("online-mode", "false");
            if (cli.containsKey("online-mode") && bool(cli.get("online-mode"), "--online-mode")) {
                throw new GradleException("--online-mode=true conflicts with the Velocity backend configuration");
            }
        } else if (cli.containsKey("online-mode")) properties.put("online-mode", cli.get("online-mode"));
        Map<Path, String> files = new LinkedHashMap<>();
        StringBuilder content = new StringBuilder("# Spring Paper development server\n");
        properties.forEach((key, value) -> content.append(OwnedRunDirectory.property(key)).append('=').append(OwnedRunDirectory.property(value)).append('\n'));
        files.put(path(root, cli.getOrDefault("config", "server.properties")), content.toString());
        files.put(root.resolve("eula.txt"), "# https://aka.ms/MinecraftEULA\neula=" + acceptEula + "\n");
        files.put(path(root, cli.getOrDefault("spigot-settings", "spigot.yml")), yaml(Map.of("settings", Map.of("bungeecord", false))));
        Map<String, Object> forwarding = new LinkedHashMap<>();
        forwarding.put("enabled", enabled);
        forwarding.put("online-mode", bool(velocity.getOrDefault("online-mode", true), "spring.paper.velocity.online-mode"));
        // Native PAPER_VELOCITY_SECRET overrides this empty value in the launcher-owned child process.
        forwarding.put("secret", "");
        files.put(path(root, cli.getOrDefault("paper-settings-directory", "config")).resolve("paper-global.yml"),
                yaml(Map.of("proxies", Map.of("velocity", forwarding))));
        if (velocity.containsKey("secret")) throw new GradleException("Use spring.paper.velocity.secret-file; inline forwarding secrets are not supported");
        Object secret = velocity.get("secret-file");
        return new Plan(files, enabled && secret != null ? path(root, secret.toString()) : null, "PAPER_VELOCITY_SECRET", enabled);
    }
    static Plan velocity(Path root, Map<String, Object> settings) {
        if (settings.containsKey("forwarding-secret")) throw new GradleException("Use spring.velocity.server.forwarding-secret-file; inline forwarding secrets are not supported");
        Map<String, Object> values = new LinkedHashMap<>(settings);
        values.putIfAbsent("config-version", "2.9");
        values.putIfAbsent("bind", "0.0.0.0:25565");
        values.putIfAbsent("online-mode", true);
        values.putIfAbsent("player-info-forwarding-mode", "none");
        values.putIfAbsent("forwarding-secret-file", "forwarding.secret");
        for (String section : List.of("servers", "forced-hosts", "advanced", "query", "metrics", "packet-limiter")) values.putIfAbsent(section, Map.of());
        CommentedConfig config = TomlFormat.instance().createConfig(LinkedHashMap::new);
        for (var entry : values.entrySet()) config.set(List.of(entry.getKey()), tomlValue(entry.getValue()));
        String toml = new TomlWriter().writeToString(config);
        // Native resolves both velocity.toml and forwarding-secret-file against process cwd, not plugin dataDir.
        Path source = path(root, values.get("forwarding-secret-file").toString());
        return new Plan(Map.of(root.resolve("velocity.toml"), toml), source, "VELOCITY_FORWARDING_SECRET", true);
    }
    private static Object tomlValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            CommentedConfig result = TomlFormat.instance().createConfig(LinkedHashMap::new);
            map.forEach((key, item) -> result.set(List.of(key.toString()), tomlValue(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(NativeConfiguration::tomlValue).toList();
        if (value instanceof Boolean || value instanceof Number || value instanceof String) return value;
        throw new GradleException("Velocity native configuration supports only scalar values, maps and lists");
    }
    static Map<String, Object> map(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw new GradleException("Launch settings must be mappings");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(key.toString(), item));
        return result;
    }
    static Path path(Path root, String value) { return root.resolve(value).toAbsolutePath().normalize(); }
    static boolean bool(Object value, String name) {
        if (value instanceof Boolean result) return result;
        if (value.toString().equalsIgnoreCase("true")) return true;
        if (value.toString().equalsIgnoreCase("false")) return false;
        throw new GradleException(name + " must be true or false");
    }
    private static String yaml(Map<String, Object> values) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(values);
    }
    private static Map<String, String> paperArguments(List<String> args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < args.size(); index++) {
            String argument = args.get(index);
            int equals = argument.indexOf('=');
            String option = equals >= 0 ? argument.substring(0, equals) : argument;
            String name = switch (option) {
                case "--config", "-c" -> "config";
                case "--spigot-settings", "-S" -> "spigot-settings";
                case "--paper-settings-directory" -> "paper-settings-directory";
                case "--online-mode", "-o" -> "online-mode";
                default -> null;
            };
            if (name == null) continue;
            if (equals < 0 && index + 1 == args.size()) throw new GradleException("Missing value for " + option);
            String value = equals >= 0 ? argument.substring(equals + 1) : args.get(++index);
            if (value.isBlank()) throw new GradleException("Missing value for " + option);
            String previous = result.putIfAbsent(name, value);
            if (previous != null && !previous.equals(value)) throw new GradleException("Conflicting native command-line values for " + option);
        }
        return result;
    }
}
