package org.blu3fishez.springpaper.gradle;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Accept native default additions and formatting; never change an existing generated value. */
final class NativeFileSemantics {
    private NativeFileSemantics() {}
    static boolean preserves(String host, String before, String after) throws IOException {
        try { return contains(parse(host, before), parse(host, after)); }
        // Parser diagnostics can quote configuration values; an invalid file is simply not preserved.
        catch (RuntimeException invalidConfiguration) { return false; }
    }
    static Object parse(String host, String value) throws IOException {
        if (host.equals("velocity")) return map(new TomlParser().parse(value));
        // Paper CLI paths need not retain their usual .yml or .properties suffix.
        if (yamlMapping(value)) return new Yaml(new SafeConstructor(new LoaderOptions())).load(value);
        Properties properties = new Properties();
        properties.load(new StringReader(value));
        return properties;
    }
    private static boolean yamlMapping(String value) {
        for (String line : value.lines().toList()) {
            String text = line.stripLeading();
            if (text.isEmpty() || text.startsWith("#") || text.startsWith("!") || text.equals("---")) continue;
            int colon = text.indexOf(':');
            int equals = text.indexOf('=');
            return colon >= 0 && (equals < 0 || colon < equals) && (colon == 0 || text.charAt(colon - 1) != '\\');
        }
        return false;
    }
    private static Object map(Object value) {
        if (value instanceof UnmodifiableConfig config) {
            Map<String, Object> result = new LinkedHashMap<>();
            config.valueMap().forEach((key, item) -> result.put(key, map(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(NativeFileSemantics::map).toList();
        return value;
    }
    private static boolean contains(Object before, Object after) {
        if (before instanceof Map<?, ?> expected) {
            if (!(after instanceof Map<?, ?> actual)) return false;
            return expected.entrySet().stream().allMatch(entry -> actual.containsKey(entry.getKey()) && contains(entry.getValue(), actual.get(entry.getKey())));
        }
        if (before instanceof List<?> expected) {
            if (!(after instanceof List<?> actual) || actual.size() != expected.size()) return false;
            for (int index = 0; index < expected.size(); index++) if (!contains(expected.get(index), actual.get(index))) return false;
            return true;
        }
        if (before instanceof Number expected && after instanceof Number actual) {
            try { return new BigDecimal(expected.toString()).compareTo(new BigDecimal(actual.toString())) == 0; }
            catch (NumberFormatException ignored) { return expected.equals(actual); }
        }
        return java.util.Objects.equals(before, after);
    }
}
