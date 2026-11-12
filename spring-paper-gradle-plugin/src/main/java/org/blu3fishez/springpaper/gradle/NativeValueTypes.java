package org.blu3fishez.springpaper.gradle;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.gradle.api.GradleException;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.support.DefaultConversionService;

/**
 * Fixed native types, not guesses based on a value's spelling.
 * Velocity 4.2.1 build 43 (git 9a5951c7): default-velocity.toml and VelocityConfiguration.
 * https://github.com/PaperMC/Velocity/blob/9a5951c7/proxy/src/main/resources/default-velocity.toml
 * https://github.com/PaperMC/Velocity/blob/9a5951c7/proxy/src/main/java/com/velocitypowered/proxy/config/VelocityConfiguration.java
 */
final class NativeValueTypes {
    private static final String PAPER = "spring.paper.velocity.";
    private static final String VELOCITY = "spring.velocity.server.";
    private static final DefaultConversionService CONVERSION = new DefaultConversionService();
    private enum Kind { BOOLEAN, INTEGER, TEXT }
    private static final Map<String, Kind> PAPER_FIELDS = fields(true);
    private static final Map<String, Kind> VELOCITY_FIELDS = fields(false);
    private static final Set<String> GROUPS = Set.of("servers", "forced-hosts", "advanced", "query", "metrics", "packet-limiter");
    record Projection(Map<String, Object> fields, Map<String, Object> maps) {
        static final Projection EMPTY = new Projection(Map.of(), Map.of());
        @Override public String toString() { return "Native field projection [values=redacted]"; }
    }
    private NativeValueTypes() {}

    static List<String> scalarFields() {
        List<String> result = new ArrayList<>();
        PAPER_FIELDS.keySet().forEach(field -> result.add(PAPER + field));
        VELOCITY_FIELDS.keySet().forEach(field -> result.add(VELOCITY + field));
        return List.copyOf(result);
    }
    static List<String> mapFields() { return GROUPS.stream().sorted().map(group -> VELOCITY + group).toList(); }

    static Map<String, Object> paper(Map<String, Object> raw, Projection projection) {
        Object enabled = projection.fields().get(PAPER + "enabled");
        if (enabled == null) enabled = raw.getOrDefault("enabled", false);
        if (!(Boolean) scalar(enabled, Kind.BOOLEAN, PAPER + "enabled")) return Map.of("enabled", false);
        return normalize(raw, PAPER, PAPER_FIELDS, Set.of(), projection);
    }
    static Map<String, Object> velocity(Map<String, Object> raw, Projection projection) {
        Map<String, Object> values = normalize(raw, VELOCITY, VELOCITY_FIELDS, GROUPS, projection);
        if (values.get("servers") instanceof Map<?, ?> servers && servers.containsKey("try")) {
            @SuppressWarnings("unchecked") var mutable = (Map<String, Object>) servers;
            mutable.put("try", strings(servers.get("try"), VELOCITY + "servers.try"));
        }
        if (values.get("forced-hosts") instanceof Map<?, ?> hosts) {
            @SuppressWarnings("unchecked") var mutable = (Map<String, Object>) hosts;
            for (String host : List.copyOf(mutable.keySet())) mutable.put(host, strings(mutable.get(host), VELOCITY + "forced-hosts"));
        }
        return values;
    }

    private static Map<String, Object> normalize(Map<String, Object> raw, String prefix, Map<String, Kind> fields,
            Set<String> groups, Projection projection) {
        Map<String, Object> selected = new LinkedHashMap<>(raw);
        for (String group : groups) {
            if (!projection.maps().containsKey(prefix + group)) continue;
            removeAliases(selected, group);
            selected.put(group, mapping(projection.maps().get(prefix + group), prefix + group));
        }
        Map<String, Object> result = canonical(selected, prefix, "", fields, groups, projection.fields());
        for (var field : fields.entrySet()) {
            String path = field.getKey();
            int dot = path.indexOf('.');
            String group = dot < 0 ? null : path.substring(0, dot);
            if ("ping-passthrough".equals(group) && result.get(group) instanceof String) continue;
            Map<String, Object> target = result;
            String leaf = path;
            if (group != null) {
                if (!result.containsKey(group) && !projection.fields().containsKey(prefix + path)) continue;
                target = mapping(result.computeIfAbsent(group, ignored -> new LinkedHashMap<>()), prefix + group);
                result.put(group, target);
                leaf = path.substring(dot + 1);
            }
            Object value = projection.fields().get(prefix + path);
            if (value == null && target.containsKey(leaf)) value = target.get(leaf);
            if (value != null) target.put(leaf, scalar(value, field.getValue(), prefix + path));
        }
        for (String group : groups) if (result.containsKey(group)) mapping(result.get(group), prefix + group);
        return result;
    }

    private static Map<String, Object> canonical(Map<String, Object> raw, String prefix, String group,
            Map<String, Kind> fields, Set<String> groups, Map<String, Object> authoritative) {
        Set<String> known = new LinkedHashSet<>();
        for (String field : fields.keySet()) {
            if (group.isEmpty()) known.add(field.contains(".") ? field.substring(0, field.indexOf('.')) : field);
            else if (field.startsWith(group + ".")) known.add(field.substring(group.length() + 1));
        }
        if (group.isEmpty()) { known.addAll(groups); known.add("secret"); known.add("forwarding-secret"); }
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            String key = canonicalName(entry.getKey(), known);
            String path = group.isEmpty() ? key : group + "." + key;
            if (known.contains(key) && fields.containsKey(path) && authoritative.containsKey(prefix + path)) continue;
            Object value = entry.getValue();
            if (group.isEmpty() && (groups.contains(key) || key.equals("ping-passthrough")) && value instanceof Map<?, ?> map) {
                value = canonical(mapping(map, prefix + key), prefix, key, fields, Set.of(), authoritative);
            }
            if (result.containsKey(key) && !java.util.Objects.equals(result.get(key), value)) {
                throw invalid(prefix + path, "an unambiguous canonical key; use a canonical YAML/JSON mapping for future fields");
            }
            result.putIfAbsent(key, value);
        }
        return result;
    }
    private static String canonicalName(String name, Set<String> known) {
        String form = alias(name);
        return known.stream().filter(candidate -> alias(candidate).equals(form)).findFirst().orElse(name);
    }
    private static String alias(String value) { return value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", ""); }
    private static void removeAliases(Map<String, Object> values, String canonical) {
        values.keySet().removeIf(key -> alias(key).equals(alias(canonical)));
    }
    private static Map<String, Object> mapping(Object value, String field) {
        if (!(value instanceof Map<?, ?> map)) throw invalid(field, "a mapping");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(key.toString(), item));
        return result;
    }
    private static Object scalar(Object value, Kind kind, String field) {
        String expected = switch (kind) { case BOOLEAN -> "a boolean"; case INTEGER -> "a 32-bit integer"; case TEXT -> "text"; };
        try {
            Object result = switch (kind) {
                case BOOLEAN -> value instanceof Boolean ? value : value instanceof String ? CONVERSION.convert(value, Boolean.class) : null;
                case INTEGER -> {
                    if (value instanceof Number number) yield new BigDecimal(number.toString()).intValueExact();
                    yield value instanceof String ? CONVERSION.convert(value, Integer.class) : null;
                }
                case TEXT -> value instanceof String || value instanceof Number || value instanceof Boolean ? CONVERSION.convert(value, String.class) : null;
            };
            if (result != null) return result;
        } catch (RuntimeException ignored) { /* Conversion exceptions include input values; never retain their cause. */ }
        throw invalid(field, expected);
    }
    private static List<String> strings(Object value, String field) {
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        if (value instanceof String) {
            try {
                Object converted = CONVERSION.convert(value, TypeDescriptor.valueOf(String.class),
                        TypeDescriptor.collection(List.class, TypeDescriptor.valueOf(String.class)));
                if (converted instanceof List<?> list) return list.stream().map(String.class::cast).toList();
            } catch (RuntimeException ignored) { /* Keep values and converter causes out of diagnostics. */ }
        }
        throw invalid(field, "a list of text values");
    }
    private static GradleException invalid(String field, String expected) {
        return new GradleException("Invalid native configuration field " + field + ": expected " + expected + " (value redacted)");
    }
    private static Map<String, Kind> fields(boolean paper) {
        Map<String, Kind> fields = new TreeMap<>();
        if (paper) {
            add(fields, Kind.BOOLEAN, "", "enabled online-mode");
            add(fields, Kind.TEXT, "", "secret-file");
            return java.util.Collections.unmodifiableMap(fields);
        }
        add(fields, Kind.BOOLEAN, "", "online-mode force-key-authentication prevent-client-proxy-connections announce-forge kick-existing-players sample-players-in-ping enable-player-address-logging");
        add(fields, Kind.INTEGER, "", "show-max-players");
        add(fields, Kind.TEXT, "", "config-version bind motd player-info-forwarding-mode forwarding-secret-file");
        add(fields, Kind.INTEGER, "advanced.", "compression-threshold compression-level login-ratelimit connection-timeout read-timeout command-rate-limit kick-after-rate-limited-commands tab-complete-rate-limit kick-after-rate-limited-tab-completes");
        add(fields, Kind.BOOLEAN, "advanced.", "haproxy-protocol proxy-protocol tcp-fast-open bungee-plugin-message-channel show-ping-requests failover-on-unexpected-server-disconnect announce-proxy-commands log-command-executions log-player-connections accepts-transfers enable-reuse-port forward-commands-if-rate-limited");
        add(fields, Kind.BOOLEAN, "query.", "enabled show-plugins");
        add(fields, Kind.INTEGER, "query.", "port");
        add(fields, Kind.TEXT, "query.", "map");
        add(fields, Kind.BOOLEAN, "metrics.", "enabled");
        add(fields, Kind.INTEGER, "packet-limiter.", "interval packets-per-second bytes-per-second decompressed-bytes-per-second");
        add(fields, Kind.BOOLEAN, "ping-passthrough.", "version players description favicon modinfo");
        return java.util.Collections.unmodifiableMap(fields);
    }
    private static void add(Map<String, Kind> fields, Kind kind, String prefix, String names) {
        for (String name : names.split(" ")) fields.put(prefix + name, kind);
    }
}
