package org.blu3fishez.springpaper.boot.config;

import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.PropertySourceOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;

/** Restores JSON primitive types only for native export after Boot selected and processed the JSON source. */
final class NativeJsonValues {
    private static final String SOURCE = "spring.application.json";
    private static final Pattern NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private final int[] input;

    private NativeJsonValues(String json) { input = json.codePoints().toArray(); }

    static void restore(ConfigurableEnvironment environment) {
        var source = environment.getPropertySources().get(SOURCE);
        if (!(source instanceof MapPropertySource values)
                || !(OriginLookup.getOrigin(source, "") instanceof PropertySourceOrigin origin)) return;
        Object original = origin.getPropertySource().getProperty(origin.getPropertyName());
        if (!(original instanceof String json)) return;
        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten("", flattened, parse(json));
        Map<String, Object> corrected = new LinkedHashMap<>(values.getSource());
        corrected.keySet().removeIf(NativeJsonValues::nativeKey);
        flattened.forEach((name, value) -> { if (nativeKey(name)) corrected.put(name, value); });
        environment.getPropertySources().replace(SOURCE, new TypedSource(corrected, source));
    }

    private static Object parse(String json) {
        try {
            json = yamlInput(json);
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setNestingDepthLimit(1000);
            options.setCodePointLimit(Integer.MAX_VALUE);
            Node node = new Yaml(new SafeConstructor(options)).compose(new StringReader(json));
            var reader = new NativeJsonValues(json);
            if (!(node instanceof MappingNode)) throw invalid();
            reader.gap(0, reader.start(node), "");
            Object value = reader.value(node);
            reader.gap(reader.end(node), reader.input.length, "");
            return value;
        } catch (RuntimeException invalid) {
            throw invalid(); // Parser messages and causes may contain private JSON values.
        }
    }

    private Object value(Node node) {
        if (node instanceof ScalarNode scalar) {
            String literal = text(start(node), end(node));
            if (literal.startsWith("\"")) return string(scalar);
            if (literal.equals("true") || literal.equals("false")) return Boolean.valueOf(literal);
            if (literal.equals("null")) return null;
            if (!NUMBER.matcher(literal).matches()) throw invalid();
            if (literal.indexOf('.') >= 0 || literal.indexOf('e') >= 0 || literal.indexOf('E') >= 0) return new BigDecimal(literal);
            BigInteger integer = new BigInteger(literal);
            return integer.bitLength() < 64 ? integer.longValue() : integer;
        }
        if (node instanceof MappingNode map && map.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            edge(node, '{', '}');
            Map<String, Object> result = new LinkedHashMap<>();
            int cursor = start(node) + 1;
            for (var tuple : map.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode key)) throw invalid();
                gap(cursor, start(key), result.isEmpty() ? "" : ",");
                String name = string(key);
                if (result.containsKey(name)) throw invalid();
                Node child = tuple.getValueNode();
                gap(end(key), start(child), ":");
                result.put(name, value(child));
                cursor = end(child);
            }
            gap(cursor, end(node) - 1, "");
            return result;
        }
        if (node instanceof SequenceNode list && list.getFlowStyle() == DumperOptions.FlowStyle.FLOW) {
            edge(node, '[', ']');
            List<Object> result = new ArrayList<>();
            int cursor = start(node) + 1;
            for (Node child : list.getValue()) {
                gap(cursor, start(child), result.isEmpty() ? "" : ",");
                result.add(value(child));
                cursor = end(child);
            }
            gap(cursor, end(node) - 1, "");
            return result;
        }
        throw invalid();
    }

    private String string(ScalarNode node) {
        String literal = text(start(node), end(node));
        if (literal.length() < 2 || literal.charAt(0) != '"' || literal.charAt(literal.length() - 1) != '"') throw invalid();
        StringBuilder decoded = new StringBuilder(literal.length() - 2);
        for (int i = 1; i < literal.length() - 1; i++) {
            char character = literal.charAt(i);
            if (character < 32 || character == '"') throw invalid();
            if (character == '\\') {
                if (++i >= literal.length() - 1) throw invalid();
                char escape = literal.charAt(i);
                if (escape == 'u') {
                    int codeUnit = 0;
                    for (int count = 0; count < 4; count++) {
                        if (++i >= literal.length() - 1 || Character.digit(literal.charAt(i), 16) < 0) throw invalid();
                        codeUnit = codeUnit * 16 + Character.digit(literal.charAt(i), 16);
                    }
                    decoded.append((char) codeUnit);
                } else decoded.append(switch (escape) {
                    case '"', '\\', '/' -> escape;
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                    default -> throw invalid();
                });
            } else decoded.append(character);
        }
        return decoded.toString();
    }

    private static String yamlInput(String json) {
        StringBuilder result = new StringBuilder(json.length());
        boolean quoted = false;
        for (int index = 0; index < json.length(); index++) {
            char character = json.charAt(index);
            if (quoted && character == '\\' && index + 1 < json.length()) {
                char escape = json.charAt(++index);
                // JSON permits escaped slash; YAML requires its equivalent Unicode escape.
                if (escape == '/') result.append("\\u002f");
                else result.append('\\').append(escape);
            } else if (quoted && (character == '\u0085' || character == '\u2028' || character == '\u2029')) {
                // Prevent YAML folding, including in a JSON object's quoted key.
                result.append(character == '\u0085' ? "\\u0085" : character == '\u2028' ? "\\u2028" : "\\u2029");
            } else {
                result.append(character);
                if (character == '"') quoted = !quoted;
            }
        }
        return result.toString();
    }

    private void edge(Node node, char first, char last) {
        if (input[start(node)] != first || input[end(node) - 1] != last) throw invalid();
    }

    private void gap(int start, int end, String delimiter) {
        if (end < start || !text(start, end).matches("[ \\t\\r\\n]*" + Pattern.quote(delimiter) + "[ \\t\\r\\n]*")) throw invalid();
    }

    private int start(Node node) { return node.getStartMark().getIndex(); }
    private int end(Node node) { return node.getEndMark().getIndex(); }
    private String text(int start, int end) { return new String(input, start, end - start); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid native JSON configuration"); }

    private static boolean nativeKey(String name) {
        for (String prefix : List.of("spring.paper.velocity", "spring.velocity.server")) {
            if (name.equals(prefix) || name.startsWith(prefix + ".") || name.startsWith(prefix + "[")) return true;
        }
        return false;
    }

    private static void flatten(String name, Map<String, Object> result, Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            map.forEach((key, child) -> flatten(name.isEmpty() ? key.toString() : name + "." + key, result, child));
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            for (int index = 0; index < list.size(); index++) flatten(name + "[" + index + "]", result, list.get(index));
        } else result.put(name, value);
    }

    private static final class TypedSource extends MapPropertySource implements OriginLookup<String> {
        private final org.springframework.core.env.PropertySource<?> original;
        TypedSource(Map<String, Object> values, org.springframework.core.env.PropertySource<?> original) {
            super(SOURCE, values);
            this.original = original;
        }
        @Override public Origin getOrigin(String key) { return OriginLookup.getOrigin(original, key); }
    }
}
