package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import org.gradle.api.GradleException;

/** Owns only files it generated. Existing unmanaged trees are always plan-only. */
final class OwnedRunDirectory {
    private static final String MANIFEST = ".spring-paper/owned.properties";
    private OwnedRunDirectory() {}
    record Snapshot(Path root, String host, String manifestHash, Map<Path, String> files) {}
    static Snapshot snapshot(Path directory, String host) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Path manifest = root.resolve(MANIFEST);
        requireSafe(root, manifest);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(manifest)) { properties.load(reader); }
        if (!host.equals(properties.remove("host"))) throw new GradleException("Run directory host changed before launch");
        Map<Path, String> files = new LinkedHashMap<>();
        for (Object key : properties.keySet()) {
            Path target = root.resolve(key.toString()).normalize();
            requireSafe(root, target);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !properties.getProperty(key.toString()).equals(hash(Files.readAllBytes(target)))) {
                throw new GradleException("Managed native configuration changed before launch: " + target);
            }
            files.put(target, Files.readString(target));
        }
        return new Snapshot(root, host, hash(Files.readAllBytes(manifest)), Map.copyOf(files));
    }
    static void rebaseline(Snapshot snapshot) throws IOException {
        Path manifest = snapshot.root().resolve(MANIFEST);
        requireSafe(snapshot.root(), manifest);
        if (!snapshot.manifestHash().equals(hash(Files.readAllBytes(manifest)))) throw new GradleException("Native ownership manifest changed during execution");
        var hashes = new TreeMap<String, String>();
        for (var entry : snapshot.files().entrySet()) {
            Path target = entry.getKey();
            requireSafe(snapshot.root(), target);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    || !NativeFileSemantics.preserves(snapshot.host(), entry.getValue(), Files.readString(target))) {
                throw new GradleException("Native execution changed or removed a generated value; ownership hashes remain unchanged: " + target);
            }
            hashes.put(snapshot.root().relativize(target).toString().replace('\\', '/'), hash(Files.readAllBytes(target)));
        }
        StringBuilder contents = new StringBuilder("host=" + snapshot.host() + "\n");
        hashes.forEach((key, value) -> contents.append(property(key)).append('=').append(value).append('\n'));
        Files.writeString(manifest, contents);
    }
    static boolean materialize(Path directory, String host, Map<Path, String> files, Path plan) throws IOException {
        return materialize(directory, host, new NativeConfiguration.Plan(files, null, null, false), plan);
    }
    static boolean materialize(Path directory, String host, NativeConfiguration.Plan preparation, Path plan) throws IOException {
        Map<Path, String> files = preparation.files();
        Path root = directory.toAbsolutePath().normalize();
        Path manifest = root.resolve(MANIFEST);
        var hashes = new TreeMap<String, String>();
        boolean owned = Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS);
        if (owned) {
            requireSafe(root, manifest);
            Properties previous = new Properties();
            try (var reader = Files.newBufferedReader(manifest)) { previous.load(reader); }
            if (!host.equals(previous.remove("host"))) throw new GradleException("Run directory belongs to a different application host: " + root);
            previous.forEach((key, value) -> hashes.put(key.toString(), value.toString()));
            for (var entry : hashes.entrySet()) {
                Path target = root.resolve(entry.getKey()).normalize();
                requireSafe(root, target);
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || !entry.getValue().equals(hash(Files.readAllBytes(target)))) {
                    throw new GradleException("Managed native configuration drift detected: " + target + ". Restore or review the file; automatic overwrite is disabled.");
                }
            }
        } else if (!Files.exists(root)) owned = true;
        else if (Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            try (var entries = Files.list(root)) { owned = entries.findAny().isEmpty(); }
        }
        Map<Path, String> normalized = new LinkedHashMap<>();
        for (var entry : files.entrySet()) {
            Path target = entry.getKey().toAbsolutePath().normalize();
            if (!safe(root, target)) owned = false;
            normalized.put(target, entry.getValue());
        }
        validateLayout(root, normalized);
        if (plan.toAbsolutePath().normalize().startsWith(root)
                || NativePlanReport.proposal(plan).toAbsolutePath().normalize().startsWith(root)) {
            throw new GradleException("Native review outputs must be outside the native run directory");
        }
        NativePlanReport.write(plan, host, owned ? "owned" : "plan-only", preparation);
        if (!owned) return false;
        // Validate every target before writing any target.
        for (Path target : normalized.keySet()) {
            requireSafe(root, target);
            String key = root.relativize(target).toString().replace('\\', '/');
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !hashes.containsKey(key)) {
                throw new GradleException("Refusing to overwrite unmanaged native configuration: " + target);
            }
        }
        for (var entry : normalized.entrySet()) {
            Files.createDirectories(entry.getKey().getParent());
            if (!Files.exists(entry.getKey()) || !Files.readString(entry.getKey()).equals(entry.getValue())) Files.writeString(entry.getKey(), entry.getValue());
            hashes.put(root.relativize(entry.getKey()).toString().replace('\\', '/'), hash(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        Files.createDirectories(manifest.getParent());
        StringBuilder contents = new StringBuilder("host=" + host + "\n");
        hashes.forEach((key, value) -> contents.append(property(key)).append('=').append(value).append('\n'));
        Files.writeString(manifest, contents);
        return true;
    }
    private static void validateLayout(Path root, Map<Path, String> files) {
        Path metadata = root.resolve(".spring-paper");
        for (Path target : files.keySet()) {
            if (target.startsWith(metadata)) throw new GradleException("Native configuration cannot replace ownership metadata: " + target);
            for (Path parent = target.getParent(); parent != null && parent.startsWith(root); parent = parent.getParent()) {
                if (files.containsKey(parent) || (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))) {
                    throw new GradleException("Native configuration parent is not a directory: " + parent);
                }
            }
        }
    }
    private static boolean safe(Path root, Path target) {
        if (!target.startsWith(root) || target.equals(root)) return false;
        for (Path current = target; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) return false;
            if (current.equals(root)) break;
        }
        return true;
    }
    private static void requireSafe(Path root, Path target) {
        if (!safe(root, target)) throw new GradleException("Managed file leaves the owned run directory or follows a symbolic link: " + target);
    }
    static String hash(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String property(String text) { return text.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("=", "\\=").replace(":", "\\:").replace(" ", "\\ "); }
}
