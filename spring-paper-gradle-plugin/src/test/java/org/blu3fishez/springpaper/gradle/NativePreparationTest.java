package org.blu3fishez.springpaper.gradle;

import static org.junit.jupiter.api.Assertions.*;
import com.electronwill.nightconfig.toml.TomlParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class NativePreparationTest {
    @TempDir Path directory;
    static java.util.stream.Stream<List<String>> identicalNativeTargets() {
        return java.util.stream.Stream.of(
                List.of("--config=eula.txt"),
                List.of("--config", "nested/../eula.txt"),
                List.of("--config", "spigot.yml"),
                List.of("--config", "config/paper-global.yml"),
                List.of("--config", "EULA.TXT"),
                List.of("--config", "nested/../EULA.TXT"),
                List.of("--config", "SPIGOT.YML"),
                List.of("--spigot-settings", "config/PAPER-GLOBAL.YML"),
                List.of("--spigot-settings", "eula.txt"),
                List.of("--spigot-settings", "config/paper-global.yml"),
                List.of("--config", "./same.cfg", "--spigot-settings", "nested/../same.cfg"));
    }
    @ParameterizedTest @MethodSource("identicalNativeTargets")
    void identicalNativeTargetsFailBeforeCreatingAnyNativeOrReviewOutput(List<String> args) throws Exception {
        Path root = directory.resolve("run");
        Path report = directory.resolve("review/native-plan.txt");
        var error = assertThrows(GradleException.class, () -> {
            var plan = NativeConfiguration.paper(root, Map.of("enabled", true), args, false, Map.of("server-port", "25565"));
            OwnedRunDirectory.materialize(root, "paper", plan, report);
        });
        assertTrue(error.getMessage().contains("Native target path collision"), error.getMessage());
        assertFalse(Files.exists(root));
        assertFalse(Files.exists(report.getParent()));
    }
    @Test void identicalNativeTargetsLeaveAnExistingTreeAndItsSecurityValuesUntouched() throws Exception {
        Path root = Files.createDirectory(directory.resolve("existing-collision"));
        Path original = root.resolve("eula.txt");
        Files.writeString(original, "# user-owned fixture\neula=false\nprivate-password=existing-fixture-value\n");
        Path report = directory.resolve("existing-review/native-plan.txt");
        assertThrows(GradleException.class, () -> {
            var plan = NativeConfiguration.paper(root, Map.of("enabled", true), List.of("--config", "eula.txt"), false, Map.of());
            OwnedRunDirectory.materialize(root, "paper", plan, report);
        });
        assertEquals("# user-owned fixture\neula=false\nprivate-password=existing-fixture-value\n", Files.readString(original));
        assertFalse(Files.exists(root.resolve(".spring-paper")));
        assertFalse(Files.exists(report.getParent()));
    }
    @Test void newOwnedFilesAreDeterministicAndManualDriftNeverGetsOverwritten() throws Exception {
        Path run = directory.resolve("run");
        var plan = NativeConfiguration.paper(run, Map.of("enabled", false), List.of(), false, Map.of("server-port", "25565"));
        Path report = directory.resolve("plan.txt");
        assertTrue(OwnedRunDirectory.materialize(run, "paper", plan.files(), report));
        String manifest = Files.readString(run.resolve(".spring-paper/owned.properties"));
        String properties = Files.readString(run.resolve("server.properties"));
        assertTrue(OwnedRunDirectory.materialize(run, "paper", plan.files(), report));
        assertEquals(manifest, Files.readString(run.resolve(".spring-paper/owned.properties")));
        assertEquals(properties, Files.readString(run.resolve("server.properties")));
        assertTrue(Files.readString(run.resolve("eula.txt")).contains("eula=false"));
        Files.writeString(run.resolve("server.properties"), "server-port=25566\n");
        assertThrows(GradleException.class, () -> OwnedRunDirectory.materialize(run, "paper", plan.files(), report));
        assertEquals("server-port=25566\n", Files.readString(run.resolve("server.properties")));
    }
    @Test void unmanagedDirectoriesAndCliPathsOutsideOwnedRootArePlanOnly() throws Exception {
        Path run = Files.createDirectory(directory.resolve("existing"));
        Files.writeString(run.resolve("server.properties"), "# user file\n");
        var existing = NativeConfiguration.paper(run, Map.of(), List.of(), false, Map.of());
        assertFalse(OwnedRunDirectory.materialize(run, "paper", existing.files(), directory.resolve("existing-plan.txt")));
        assertEquals("# user file\n", Files.readString(run.resolve("server.properties")));
        assertFalse(Files.exists(run.resolve("eula.txt")));
        Path fresh = directory.resolve("fresh");
        Path external = directory.resolve("external.properties");
        var outside = NativeConfiguration.paper(fresh, Map.of(), List.of("--config", external.toString()), false, Map.of());
        assertFalse(OwnedRunDirectory.materialize(fresh, "paper", outside.files(), directory.resolve("outside-plan.txt")));
        assertFalse(Files.exists(fresh));
        assertFalse(Files.exists(external));
    }
    @Test void paperUsesEffectiveCliPathsAndRejectsContradictoryOnlineMode() {
        Path run = directory.resolve("run");
        var values = Map.<String, Object>of("enabled", true, "online-mode", true);
        var plan = NativeConfiguration.paper(run, values, List.of("--config=custom.properties", "--spigot-settings", "custom-spigot.yml", "--paper-settings-directory", "paper-settings", "--online-mode=false"), false, Map.of());
        assertTrue(plan.files().containsKey(run.resolve("custom.properties")));
        assertTrue(plan.files().containsKey(run.resolve("custom-spigot.yml")));
        assertTrue(plan.files().containsKey(run.resolve("paper-settings/paper-global.yml")));
        assertTrue(plan.files().get(run.resolve("custom.properties")).contains("online-mode=false"));
        assertThrows(GradleException.class, () -> NativeConfiguration.paper(run, values, List.of("--online-mode", "true"), false, Map.of()));
    }
    @Test void velocityTomlPreservesBooleansNumbersListsAndLiteralDottedKeys() {
        var plan = NativeConfiguration.velocity(directory, Map.of("online-mode", false, "show-max-players", 42,
                "servers", Map.of("island.test", "[::1]:25565", "try", List.of("island.test")),
                "forced-hosts", Map.of("play.example.test", List.of("island.test"))));
        var toml = new TomlParser().parse(plan.files().get(directory.resolve("velocity.toml")));
        assertEquals(false, toml.get("online-mode"));
        assertEquals(42, ((Number) toml.get("show-max-players")).intValue());
        assertEquals(List.of("island.test"), toml.get("servers.try"));
        assertEquals("[::1]:25565", toml.get(List.of("servers", "island.test")));
        assertEquals(List.of("island.test"), toml.get(List.of("forced-hosts", "play.example.test")));
    }
    @Test void fileSecretMapsToNativeEnvironmentWithoutWritingItAndConflictsStayRedacted() throws Exception {
        Path source = directory.resolve("shared.secret");
        Files.writeString(source, "sensitive-example-value\n");
        var plan = NativeConfiguration.paper(directory.resolve("run"), Map.of("enabled", true, "secret-file", source.toString()), List.of(), false, Map.of());
        assertEquals("sensitive-example-value", ForwardingSecret.resolve(plan, Map.of()));
        assertFalse(plan.files().values().stream().anyMatch(value -> value.contains("sensitive-example-value")));
        var conflict = assertThrows(GradleException.class, () -> ForwardingSecret.resolve(plan, Map.of("PAPER_VELOCITY_SECRET", "different-value")));
        assertFalse(conflict.getMessage().contains("sensitive-example-value"));
        assertFalse(conflict.getMessage().contains("different-value"));
        var disabled = NativeConfiguration.paper(directory.resolve("disabled"), Map.of("enabled", false, "secret-file", "missing"), List.of(), false, Map.of());
        assertNull(ForwardingSecret.resolve(disabled, Map.of()));
    }
    @Test void successfulNativeFormattingAndDefaultAdditionsCanBeRebaselinedButValueChangesCannot() throws Exception {
        Path root = directory.resolve("run");
        Path file = root.resolve("velocity.toml");
        var generated = Map.of(file, "online-mode = true\nshow-max-players = 42\n[servers]\ntry = [\"lobby\"]\n");
        OwnedRunDirectory.materialize(root, "velocity", generated, directory.resolve("plan.txt"));
        var snapshot = OwnedRunDirectory.snapshot(root, "velocity");
        Files.writeString(file, "# native formatting\nonline-mode=true\nshow-max-players=42\nconfig-version=\"2.9\"\n[servers]\ntry=[\"lobby\"]\nlobby=\"127.0.0.1:25565\"\n");
        OwnedRunDirectory.rebaseline(snapshot);
        assertTrue(OwnedRunDirectory.materialize(root, "velocity", generated, directory.resolve("plan.txt")));
        snapshot = OwnedRunDirectory.snapshot(root, "velocity");
        String originalManifest = Files.readString(root.resolve(".spring-paper/owned.properties"));
        Files.writeString(file, "online-mode=false\nshow-max-players=42\n[servers]\ntry=[\"lobby\"]\n");
        var beforeFailure = snapshot;
        assertThrows(GradleException.class, () -> OwnedRunDirectory.rebaseline(beforeFailure));
        assertEquals(originalManifest, Files.readString(root.resolve(".spring-paper/owned.properties")));
        assertFalse(NativeFileSemantics.preserves("velocity", "online-mode=true\n", "online-mode=\"true\"\n"));
        assertFalse(NativeFileSemantics.preserves("velocity", "try=[1,2]\n", "try=[2,1]\n"));
    }
    @Test void rejectsCollidingNativePathsBeforeWritingAnyConfiguration() throws Exception {
        Path root = directory.resolve("colliding");
        var plan = NativeConfiguration.paper(root, Map.of(), List.of("--config", "config"), false, Map.of());
        assertThrows(GradleException.class, () -> OwnedRunDirectory.materialize(root, "paper", plan.files(), directory.resolve("collision-plan.txt")));
        assertFalse(Files.exists(root));
    }
    @Test void ownershipMetadataCannotBeClaimedAsANativeCliTarget() throws Exception {
        Path root = directory.resolve("reserved");
        var plan = NativeConfiguration.paper(root, Map.of(), List.of("--config", ".spring-paper/owned.properties"), false, Map.of());
        assertThrows(GradleException.class, () -> OwnedRunDirectory.materialize(root, "paper", plan.files(), directory.resolve("reserved-plan.txt")));
        assertFalse(Files.exists(root));
    }
    @Test void snapshotRejectsChangesThatArriveAfterPreparationBeforeTheProcessStarts() throws Exception {
        Path root = directory.resolve("changed");
        Path file = root.resolve("velocity.toml");
        OwnedRunDirectory.materialize(root, "velocity", Map.of(file, "online-mode=true\n"), directory.resolve("changed-plan.txt"));
        Files.writeString(file, "online-mode=false\n");
        assertThrows(GradleException.class, () -> OwnedRunDirectory.snapshot(root, "velocity"));
        assertEquals("online-mode=false\n", Files.readString(file));
    }
    @Test void invalidNativeConfigNeverLeaksItsParserExcerptAndNeverRebaselines() throws Exception {
        Path root = directory.resolve("invalid");
        Path file = root.resolve("velocity.toml");
        OwnedRunDirectory.materialize(root, "velocity", Map.of(file, "online-mode=true\n"), directory.resolve("invalid-plan.txt"));
        var snapshot = OwnedRunDirectory.snapshot(root, "velocity");
        String manifest = Files.readString(root.resolve(".spring-paper/owned.properties"));
        Files.writeString(file, "online-mode = [sensitive-example-value\n");
        var error = assertThrows(GradleException.class, () -> OwnedRunDirectory.rebaseline(snapshot));
        assertFalse(error.getMessage().contains("sensitive-example-value"));
        assertNull(error.getCause());
        assertEquals(manifest, Files.readString(root.resolve(".spring-paper/owned.properties")));
    }
    @Test void unmanagedPlanShowsProposedValuesWhileRedactingSensitiveKeysAndPreservingOriginalFiles() throws Exception {
        Path root = Files.createDirectory(directory.resolve("unmanaged-proposal"));
        Path original = root.resolve("server.properties");
        Files.writeString(original, "user-owned=true\n");
        var settings = Map.of("server-port", "25565", "rcon.password", "sensitive-password-value", "api-token", "sensitive-token-value");
        var plan = NativeConfiguration.paper(root, Map.of("enabled", true, "secret-file", "unavailable-shared.secret"), List.of(), false, settings);
        Path report = directory.resolve("report/native-plan.txt");
        assertFalse(OwnedRunDirectory.materialize(root, "paper", plan, report));
        String proposal = Files.readString(report.resolveSibling("native-proposal.json"));
        assertTrue(proposal.contains("plan-only"));
        assertTrue(proposal.contains("online-mode"));
        assertTrue(proposal.contains("25565"));
        assertTrue(proposal.contains("<redacted>"));
        assertFalse(proposal.contains("sensitive-password-value"));
        assertFalse(proposal.contains("sensitive-token-value"));
        assertTrue(proposal.contains("unavailable-shared.secret"));
        assertTrue(proposal.contains("PAPER_VELOCITY_SECRET"));
        assertTrue(proposal.contains("owned native child process environment"));
        assertTrue(Files.readString(report).contains("native-proposal.json"));
        assertEquals("user-owned=true\n", Files.readString(original));
        assertFalse(Files.exists(root.resolve(".spring-paper")));
    }
    @Test void reviewOutputsCannotReplaceNativeFilesInAnUnmanagedTree() throws Exception {
        Path root = Files.createDirectory(directory.resolve("protected-review"));
        Path original = root.resolve("server.properties");
        Files.writeString(original, "user-owned=true\n");
        var plan = NativeConfiguration.paper(root, Map.of(), List.of(), false, Map.of());
        assertThrows(GradleException.class, () -> OwnedRunDirectory.materialize(root, "paper", plan, original));
        assertEquals("user-owned=true\n", Files.readString(original));
    }
    @Test void paperSemanticsFollowTheNativeFormatRegardlessOfACustomCliFileSuffix() throws Exception {
        assertTrue(NativeFileSemantics.preserves("paper", "settings:\n  bungeecord: false\n", "# native defaults\n_version: 30\nsettings:\n  bungeecord: false\n  timeout: 60\n"));
        assertFalse(NativeFileSemantics.preserves("paper", "settings:\n  bungeecord: false\n", "settings:\n  bungeecord: true\n"));
        assertTrue(NativeFileSemantics.preserves("paper", "server-port=25565\n", "# native formatting\nserver-port = 25565\nonline-mode=false\n"));
    }
}
