package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import groovy.json.JsonSlurper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.blu3fishez.springpaper.boot.config.ConfigDataProbe;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ValueSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Real product ConfigData helper and native TOML parser; no native proxy process is started. */
class NativeConfigDataTypesTest {
    @TempDir Path directory;
    private static final String PREFIX = "spring.velocity.server.";
    private static final String BASE = """
            spring.velocity.server:
              online-mode: false
              show-max-players: 5
              motd: 'true'
              config-version: '2.9'
              advanced:
                compression-threshold: 16
                tcp-fast-open: false
              query:
                enabled: false
                port: 25565
                show-plugins: false
                map: '123'
              servers:
                '[lobby.eu]': '[::1]:25565'
                '123': '127.0.0.1:25566'
                try: [lobby.eu]
              forced-hosts:
                localhost: [lobby.eu]
                '[play.example.org]': [lobby.eu]
              packet-limiter:
                interval: 7
                future-typed: { '[literal.dot]': [true, 17] }
              future-value: 'true'
              future-typed: { '[online.mode]': true }
              '[advanced.compression-threshold]': '0009'
            """;

    @ParameterizedTest @ValueSource(strings = {"properties", "environment", "jvm"})
    void realStringSourcesProduceNativeBooleansIntegersAndListsWithoutGuessingText(String source) throws Exception {
        Map<String, String> properties = Map.ofEntries(
                Map.entry(PREFIX + "online-mode", "true"), Map.entry(PREFIX + "show-max-players", "37"),
                Map.entry(PREFIX + "advanced.compression-threshold", "128"), Map.entry(PREFIX + "advanced.tcp-fast-open", "on"),
                Map.entry(PREFIX + "query.port", "25677"), Map.entry(PREFIX + "query.show-plugins", "1"),
                Map.entry(PREFIX + "servers.try", "lobby.eu,123"), Map.entry(PREFIX + "forced-hosts.localhost", "123,lobby.eu"),
                Map.entry(PREFIX + "packet-limiter.interval", "11"));
        Map<String, String> environment = new LinkedHashMap<>();
        Map<String, String> jvm = new LinkedHashMap<>();
        String external = "";
        if (source.equals("properties")) external = properties.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue() + "\n").collect(java.util.stream.Collectors.joining());
        if (source.equals("environment")) properties.forEach((key, value) -> environment.put(key.toUpperCase(java.util.Locale.ROOT).replace("-", "").replace('.', '_'), value));
        if (source.equals("jvm")) jvm.putAll(properties);
        var loaded = probe(BASE, external, environment, jvm);
        var toml = toml(loaded);
        assertEquals(true, toml.get("online-mode"));
        assertEquals(37, toml.getInt("show-max-players"));
        assertEquals(128, toml.getInt("advanced.compression-threshold"));
        assertEquals(true, toml.get("advanced.tcp-fast-open"));
        assertEquals(25677, toml.getInt("query.port"));
        assertEquals(true, toml.get("query.show-plugins"));
        assertEquals(11, toml.getInt("packet-limiter.interval"));
        assertEquals(List.of("lobby.eu", "123"), toml.get("servers.try"));
        assertEquals(List.of("123", "lobby.eu"), toml.get("forced-hosts.localhost"));
        assertEquals(List.of("lobby.eu"), toml.get(List.of("forced-hosts", "play.example.org")));
        assertEquals("[::1]:25565", toml.get(List.of("servers", "lobby.eu")));
        assertEquals("127.0.0.1:25566", toml.get(List.of("servers", "123")));
        assertEquals("true", toml.get("motd"));
        assertEquals("123", toml.get("query.map"));
        assertEquals("2.9", toml.get("config-version"));
        assertEquals("true", toml.get("future-value"));
        assertEquals(true, toml.get(List.of("future-typed", "online.mode")));
        assertEquals(List.of(true, 17), toml.get(List.of("packet-limiter", "future-typed", "literal.dot")));
        assertEquals("0009", toml.get(List.of("advanced.compression-threshold")));
        assertFalse(toml.contains("onlinemode"));
        assertFalse(toml.contains("packetlimiter"));
        assertFalse(toml.contains("forcedhosts"));
    }

    @Test void authoritativeScalarAndGroupSelectionPreservesJvmOverEnvironmentOverYaml() throws Exception {
        var loaded = probe(BASE, "", Map.of("SPRING_VELOCITY_SERVER_ONLINEMODE", "true",
                "SPRING_VELOCITY_SERVER_SHOWMAXPLAYERS", "37", "SPRING_VELOCITY_SERVER_PACKETLIMITER_INTERVAL", "11",
                "SPRING_VELOCITY_SERVER_FORCEDHOSTS_LOCALHOST_0", "123"),
                Map.of(PREFIX + "online-mode", "false", PREFIX + "show-max-players", "41",
                        PREFIX + "packet-limiter.interval", "13", PREFIX + "forced-hosts.localhost[0]", "lobby.eu"));
        var toml = toml(loaded);
        assertEquals(false, toml.get("online-mode"));
        assertEquals(41, toml.getInt("show-max-players"));
        assertEquals(13, toml.getInt("packet-limiter.interval"));
        assertEquals(List.of("lobby.eu"), toml.get("forced-hosts.localhost"));
        assertEquals(List.of("lobby.eu"), toml.get(List.of("forced-hosts", "play.example.org")));
    }

    @Test void paperBooleanOverridesUseTheSameSmallConversionAndDisabledPaperSkipsUnusedFields() throws Exception {
        var loaded = probe("spring.paper.velocity.enabled: true\nspring.paper.velocity.online-mode: true\n", "",
                Map.of("SPRING_PAPER_VELOCITY_ONLINEMODE", "on"), Map.of("spring.paper.velocity.online-mode", "off"));
        var plan = NativeConfiguration.paper(directory.resolve("paper"), NativeConfiguration.map(loaded.get("paper-velocity")), List.of(), false, Map.of(), projection(loaded));
        String yaml = plan.files().get(directory.resolve("paper/config/paper-global.yml"));
        assertTrue(yaml.contains("online-mode: false"), yaml);
        var disabled = NativeConfiguration.paper(directory.resolve("disabled"), Map.of("enabled", false,
                "online-mode", "${UNSET_UNUSED_VALUE}", "secret-file", "${UNSET_UNUSED_SECRET}"), List.of(), false, Map.of());
        assertFalse(disabled.requiresSecret());
    }

    static java.util.stream.Stream<Map<String, Object>> invalidValues() {
        return java.util.stream.Stream.of(Map.of("online-mode", "sensitive-invalid-value"),
                Map.of("show-max-players", "sensitive-invalid-value"),
                Map.of("advanced", Map.of("compression-threshold", "sensitive-invalid-value")),
                Map.of("query", Map.of("port", "2147483648-sensitive-invalid-value")),
                Map.of("servers", Map.of("try", List.of(17))),
                Map.of("online-mode", 17), Map.of("show-max-players", 3.5));
    }
    @ParameterizedTest @MethodSource("invalidValues")
    void invalidKnownValuesFailBeforeNativeWritesWithoutLeakingConverterCauses(Map<String, Object> values) throws Exception {
        Path root = directory.resolve("invalid");
        Path report = directory.resolve("review/native-plan.txt");
        var error = assertThrows(GradleException.class, () -> {
            var plan = NativeConfiguration.velocity(root, values);
            OwnedRunDirectory.materialize(root, "velocity", plan, report);
        });
        StringWriter trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertTrue(error.getMessage().contains("spring.velocity.server."));
        assertTrue(error.getMessage().contains("expected"));
        assertNull(error.getCause());
        assertFalse(trace.toString().contains("sensitive-invalid-value"));
        assertFalse(trace.toString().contains("ConversionFailedException"));
        assertFalse(Files.exists(root));
        assertFalse(Files.exists(report.getParent()));
    }

    @Test void realBadJvmValueIsReportedByFieldWithoutItsRawValueOrStacktraceCause() throws Exception {
        var loaded = probe(BASE, "", Map.of(), Map.of(PREFIX + "advanced.compression-threshold", "sensitive-invalid-value"));
        var error = assertThrows(GradleException.class, () -> toml(loaded));
        assertTrue(error.getMessage().contains(PREFIX + "advanced.compression-threshold"));
        assertFalse(error.getMessage().contains("sensitive-invalid-value"));
        assertNull(error.getCause());
    }

    @Test void paperBadOnlineModeIsRedactedBeforeAnyNativeWrites() throws Exception {
        var loaded = probe("spring.paper.velocity.enabled: true\n", "", Map.of(), Map.of("spring.paper.velocity.online-mode", "sensitive-invalid-value"));
        Path root = directory.resolve("bad-paper");
        var error = assertThrows(GradleException.class, () -> NativeConfiguration.paper(root,
                NativeConfiguration.map(loaded.get("paper-velocity")), List.of(), false, Map.of(), projection(loaded)));
        assertTrue(error.getMessage().contains("spring.paper.velocity.online-mode"));
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("sensitive-invalid-value"));
        assertFalse(Files.exists(root));
    }

    @Test void aTypedYamlNumberIsNotGuessedIntoABooleanThroughAStringProjection() throws Exception {
        var loaded = probe("spring.velocity.server.online-mode: 1\n", "", Map.of(), Map.of());
        var error = assertThrows(GradleException.class, () -> toml(loaded));
        assertTrue(error.getMessage().contains(PREFIX + "online-mode"));
        assertNull(error.getCause());
    }

    @Test void anIntegralYamlNumberKeepsItsMeaningWhenProjectedIntoAnIntegerField() throws Exception {
        var loaded = probe("spring.velocity.server.show-max-players: 37.0\n", "", Map.of(), Map.of());
        assertEquals(37, toml(loaded).getInt("show-max-players"));
    }

    @Test void typedJsonFutureValuesAndLiteralRootKeysStayDistinctFromTheKnownSchema() throws Exception {
        String json = """
                {"spring":{"velocity":{"server":{
                    "online-mode":true,"motd":"true",
                    "future-json":{"[literal.dot]":[true,17,"123"]},
                    "[advanced.compression-threshold]":"json-literal",
                    "packet-limiter":{"future-json":{"[literal.dot]":[false,23]}}
                }}}}
                """;
        var toml = toml(probe(BASE, "", Map.of("SPRING_APPLICATION_JSON", json), Map.of()));
        assertEquals(true, toml.get("online-mode"));
        assertEquals("true", toml.get("motd"));
        List<?> future = toml.get(List.of("future-json", "literal.dot"));
        assertEquals(true, future.get(0));
        assertEquals(17, ((Number) future.get(1)).intValue());
        assertEquals("123", future.get(2));
        List<?> packet = toml.get(List.of("packet-limiter", "future-json", "literal.dot"));
        assertEquals(false, packet.get(0));
        assertEquals(23, ((Number) packet.get(1)).intValue());
        assertEquals("json-literal", toml.get(List.of("advanced.compression-threshold")));
        assertEquals(16, toml.getInt("advanced.compression-threshold"));
    }

    @Test void invalidPaperEnabledIsDeferredToARedactedFieldErrorWithoutResolvingUnusedPlaceholders() throws Exception {
        for (String value : List.of("1", "sensitive-invalid-value")) {
            var loaded = probe("spring.paper.velocity:\n  enabled: " + value
                    + "\n  secret-file: '${UNAVAILABLE_TYPED_SCHEMA_SECRET_FILE}'\n  online-mode: '${UNAVAILABLE_TYPED_SCHEMA_ONLINE_MODE}'\n", "", Map.of(), Map.of());
            var error = assertThrows(GradleException.class, () -> NativeConfiguration.paper(directory.resolve("bad-enabled"),
                    NativeConfiguration.map(loaded.get("paper-velocity")), List.of(), false, Map.of(), projection(loaded)));
            assertTrue(error.getMessage().contains("spring.paper.velocity.enabled"));
            assertFalse(error.getMessage().contains("sensitive-invalid-value"));
            assertNull(error.getCause());
        }
    }

    static java.util.stream.Stream<Arguments> unicodeWireCases() {
        return java.util.stream.IntStream.of(0x85, 0x2028, 0x2029).boxed()
                .flatMap(point -> java.util.stream.Stream.of(Arguments.of(point, false), Arguments.of(point, true)));
    }
    @ParameterizedTest @MethodSource("unicodeWireCases")
    void jsonCodepointsSurviveTheRealHelperYamlTransportNativeTomlAndProposal(int point, boolean escaped) throws Exception {
        String separator = new String(Character.toChars(point));
        String value = "A" + separator + "한글🚀\b\f\n\r\t" + (char) 0 + "B";
        String key = "literal." + separator + "한글🚀";
        String json = "{\"spring\":{\"velocity\":{\"server\":{\"motd\":" + jsonQuote(value, escaped)
                + ",\"future-wire\":{" + jsonQuote("[" + key + "]", escaped) + ":" + jsonQuote(value, escaped) + "}}}}}";
        var loaded = probe(BASE, "", Map.of("SPRING_APPLICATION_JSON", json), Map.of());
        var raw = NativeConfiguration.map(loaded.get("velocity-server"));
        assertCodepoints(value, raw.get("motd"));
        assertCodepoints(value, projection(loaded).fields().get(PREFIX + "motd"));
        assertWireMap(key, value, raw.get("future-wire"));
        Path root = directory.resolve("unicode-run");
        Path report = directory.resolve("unicode-review/native-plan.txt");
        var plan = NativeConfiguration.velocity(root, raw, projection(loaded));
        assertTrue(OwnedRunDirectory.materialize(root, "velocity", plan, report));
        var nativeConfig = new TomlParser().parse(Files.readString(root.resolve("velocity.toml")));
        assertCodepoints(value, nativeConfig.get("motd"));
        assertCodepoints(value, nativeConfig.get(List.of("future-wire", key)));
        Map<?, ?> proposal = (Map<?, ?>) new JsonSlurper().parseText(Files.readString(report.resolveSibling("native-proposal.json")));
        Map<?, ?> target = (Map<?, ?>) ((List<?>) proposal.get("targets")).getFirst();
        Map<?, ?> proposed = (Map<?, ?>) target.get("proposed");
        assertCodepoints(value, proposed.get("motd"));
        assertWireMap(key, value, proposed.get("future-wire"));
    }
    private static void assertWireMap(String key, String value, Object actual) {
        Map<?, ?> map = assertInstanceOf(Map.class, actual);
        assertEquals(1, map.size());
        Object actualKey = map.keySet().iterator().next();
        assertCodepoints(key, actualKey);
        assertCodepoints(value, map.get(actualKey));
    }
    private static void assertCodepoints(String expected, Object actual) {
        String text = assertInstanceOf(String.class, actual);
        assertArrayEquals(expected.codePoints().toArray(), text.codePoints().toArray());
    }
    private static String jsonQuote(String value, boolean escapeSeparators) {
        StringBuilder result = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\b' -> result.append("\\b");
                case '\f' -> result.append("\\f");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (character < 32 || (escapeSeparators && (character == 0x85 || character == 0x2028 || character == 0x2029))) {
                        result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) character));
                    } else result.append(character);
                }
            }
        }
        return result.append('"').toString();
    }

    private UnmodifiableConfig toml(Map<String, Object> loaded) {
        var plan = NativeConfiguration.velocity(directory.resolve("velocity"), NativeConfiguration.map(loaded.get("velocity-server")), projection(loaded));
        return new TomlParser().parse(plan.files().get(directory.resolve("velocity/velocity.toml")));
    }
    private NativeValueTypes.Projection projection(Map<String, Object> loaded) {
        return new NativeValueTypes.Projection(NativeConfiguration.map(loaded.get("native-field-values")), NativeConfiguration.map(loaded.get("native-map-values")));
    }
    private Map<String, Object> probe(String yaml, String externalProperties, Map<String, String> overrides, Map<String, String> properties) throws Exception {
        Path artifact = directory.resolve("application.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            jar.putNextEntry(new JarEntry("application.yml")); jar.write(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8)); jar.closeEntry();
        }
        Path data = Files.createDirectories(directory.resolve("data"));
        if (!externalProperties.isEmpty()) Files.writeString(data.resolve("application.properties"), externalProperties);
        Path output = directory.resolve("output.yml");
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        properties.forEach((key, value) -> command.add("-D" + key + "=" + value));
        command.addAll(List.of("-cp", helperClasspath(), ConfigDataProbe.class.getName(), "--jar", artifact.toString(), "--data-dir", data.toString(), "--output", output.toString()));
        NativeValueTypes.scalarFields().forEach(field -> command.addAll(List.of("--native-field", field)));
        NativeValueTypes.mapFields().forEach(field -> command.addAll(List.of("--native-map", field)));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key -> key.startsWith("SPRING_"));
        builder.environment().putAll(overrides);
        Process child = builder.start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Product helper timed out");
            assertEquals(0, child.exitValue(), "Product helper failed; output suppressed to protect configuration values");
            return NativeConfiguration.map(new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(output)));
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Product helper survived test cleanup");
            Files.deleteIfExists(output);
        }
    }
    private String helperClasspath() throws Exception {
        List<Class<?>> types = List.of(ConfigDataProbe.class, org.springframework.boot.SpringApplication.class,
                org.springframework.context.ApplicationContext.class, org.springframework.core.io.DefaultResourceLoader.class,
                org.springframework.beans.factory.BeanFactory.class, org.springframework.aop.TargetClassAware.class,
                org.springframework.expression.Expression.class, org.apache.commons.logging.LogFactory.class, Yaml.class,
                org.blu3fishez.springpaper.config.PaperConfiguration.class);
        List<String> paths = new ArrayList<>();
        for (Class<?> type : types) paths.add(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return paths.stream().distinct().collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }
}
