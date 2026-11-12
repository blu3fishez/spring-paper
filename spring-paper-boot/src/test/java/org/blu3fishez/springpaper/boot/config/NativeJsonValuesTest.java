package org.blu3fishez.springpaper.boot.config;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.json.BasicJsonParser;
import org.springframework.boot.json.JsonParserFactory;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.DefaultResourceLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class NativeJsonValuesTest {
    @TempDir Path directory;

    @Test void restoresNativeJsonPrimitivesWithoutReinterpretingQuotedStringsOrOtherNamespaces() throws Exception {
        assertInstanceOf(BasicJsonParser.class, JsonParserFactory.getJsonParser());
        assertEquals("true", JsonParserFactory.getJsonParser().parseMap("{\"flag\":true}").get("flag"));
        Path artifact = Files.createDirectory(directory.resolve("classes"));
        Files.writeString(artifact.resolve("application.yml"), "spring.velocity.server.future.null-value: fallback\n");
        String json = """
                {"spring":{"velocity":{"server":{"future":{
                    "[literal.dot]":[true,false,17,1.5,1e3,"true","00042","2026-11-13","한글🚀","\\uD55C\\uAE00\\uD83D\\uDE80"],
                    "null-value":null,"null-only":null,"nested":{"flag":true}
                }}}},"outside":{"flag":true}}
                """;
        var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader(),
                "--spring.application.json=" + json);
        var source = environment.getPropertySources().get("spring.application.json");
        assertNull(source.getProperty("spring.velocity.server.future.null-only"));
        assertEquals("fallback", environment.getProperty("spring.velocity.server.future.null-value"));
        assertEquals("true", source.getProperty("outside.flag"), "Unrelated Boot JSON behavior is unchanged");
        var settings = ConfigDataSettings.launchSettings(environment);
        var future = (Map<?, ?>) ((Map<?, ?>) settings.get("velocity-server")).get("future");
        List<?> list = (List<?>) future.get("literal.dot");
        assertEquals(List.of(true, false), list.subList(0, 2));
        assertEquals(17, ((Number) list.get(2)).intValue());
        assertEquals(1.5, ((Number) list.get(3)).doubleValue());
        assertEquals(1000.0, ((Number) list.get(4)).doubleValue());
        assertEquals(List.of("true", "00042", "2026-11-13", "한글🚀", "한글🚀"), list.subList(5, 10));
        assertEquals(Map.of("flag", true), future.get("nested"));
        assertFalse(settings.containsKey("outside"));
    }

    @Test void preservesBootJsonOriginAndSourceOrderAgainstSystemEnvironmentAndCliValues() throws Exception {
        Path artifact = Files.createDirectory(directory.resolve("classes"));
        var loader = new DefaultResourceLoader(getClass().getClassLoader());
        var environment = ConfigDataSettings.prepare(artifact, directory.resolve("data"));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new MapPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, Map.of(
                        "spring.application.json", "{\"spring.velocity.server.future\":true}",
                        "spring.velocity.server.future", "system")));
        environment.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of(
                        "SPRING_APPLICATION_JSON", "{\"spring.velocity.server.future\":false}",
                        "SPRING_VELOCITY_SERVER_FUTURE", "environment")));
        new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication(loader));
        ConfigDataEnvironmentPostProcessor.applyTo(environment, loader, null);
        var order = environment.getPropertySources().stream().map(source -> source.getName()).toList();
        NativeJsonValues.restore(environment);
        assertEquals(order, environment.getPropertySources().stream().map(source -> source.getName()).toList());
        assertEquals(true, ((Map<?, ?>) ConfigDataSettings.launchSettings(environment).get("velocity-server")).get("future"));
        environment.getPropertySources().addFirst(new SimpleCommandLinePropertySource("--spring.velocity.server.future=command-line"));
        assertEquals("command-line", ((Map<?, ?>) ConfigDataSettings.launchSettings(environment).get("velocity-server")).get("future"));
    }

    @Test void nullArrayElementsKeepTheExistingBindingFailureInsteadOfBecomingStrings() throws Exception {
        Path artifact = Files.createDirectory(directory.resolve("classes"));
        var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader(),
                "--spring.application.json={\"spring.velocity.server.future\":[null,true]}");
        assertThrows(org.springframework.boot.context.properties.bind.BindException.class,
                () -> ConfigDataSettings.launchSettings(environment));
    }

    @ParameterizedTest @ValueSource(ints = { 0x85, 0x2028, 0x2029 })
    void rawJsonLineSeparatorsAndEscapesSurviveKeysValuesAndTheYamlTransport(int separator) throws Exception {
        Path artifact = Files.createDirectory(directory.resolve("classes"));
        String key = "A" + Character.toString(separator) + "B🚀";
        String expected = key + "\"\\/\b\f\n\r\t\u0000";
        String literal = key + "\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000";
        String json = "{\"spring\":{\"velocity\":{\"server\":{\"motd\":\"" + literal
                + "\",\"future\":{\"[" + key + "]\":\"" + literal + "\"}}}}}";
        var environment = ConfigDataSettings.load(artifact, directory.resolve("data"), getClass().getClassLoader(),
                "--spring.application.json=" + json);
        var settings = ConfigDataSettings.launchSettings(environment, List.of("spring.velocity.server.motd"));
        var nativeValues = (Map<?, ?>) settings.get("velocity-server");
        assertEquals(expected, nativeValues.get("motd"));
        assertEquals(Map.of(key, expected), nativeValues.get("future"));
        assertEquals(expected, ((Map<?, ?>) settings.get("native-field-values")).get("spring.velocity.server.motd"));
        Path output = directory.resolve("transport.yml");
        ConfigDataProbe.main(new String[] { "--jar", artifact.toString(), "--data-dir", directory.resolve("data").toString(),
                "--output", output.toString(), "--native-field", "spring.velocity.server.motd", "--spring.application.json=" + json });
        Map<?, ?> transported = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(output));
        assertEquals(settings, transported);
    }

    @Test void aRawLineSeparatorOutsideAJsonStringIsStillRejectedByBoot() throws Exception {
        Path artifact = Files.createDirectory(directory.resolve("classes"));
        assertThrows(IllegalArgumentException.class, () -> ConfigDataSettings.load(artifact,
                directory.resolve("data"), getClass().getClassLoader(),
                "--spring.application.json={\u0085\"spring.velocity.server.future\":true}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"spring.velocity.server.future\":yes}",
            "{\"spring.velocity.server.future\":01}",
            "{\"spring.velocity.server.future\":true,}",
            "{\"spring.velocity.server.future\":[true,]}",
            "{\"spring.velocity.server.future\":1,\"spring.velocity.server.future\":2}",
            "{\"spring.velocity.server.future\":\"\\x41\"}",
            "{\"spring.velocity.server.future\":true # comment\n}"
    })
    void rejectsYamlOnlyOrDuplicateSyntaxInsteadOfChangingJsonMeaning(String json) throws Exception {
        Path artifact = Files.createDirectory(directory.resolve("classes"));
        var error = assertThrows(IllegalArgumentException.class, () -> ConfigDataSettings.load(artifact,
                directory.resolve("data"), getClass().getClassLoader(), "--spring.application.json=" + json));
        assertEquals("Invalid native JSON configuration", error.getMessage());
        assertNull(error.getCause());
    }
}
