package org.blu3fishez.springpaper.key;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.NamespacedKey;

/** Plugin-owned key construction without exposing host lifecycle operations to callers. */
public final class PaperKeys {
    private final String namespace;
    public PaperKeys(String namespace) { this.namespace = Objects.requireNonNull(namespace); }
    public String namespace() { return namespace; }
    /** Preserves the normalization of {@code new NamespacedKey(plugin, path)}. */
    public NamespacedKey key(String path) {
        return new NamespacedKey(namespace, Objects.requireNonNull(path).toLowerCase(Locale.ROOT));
    }
    /** Strict native parsing; absent/empty namespaces default to this plugin. Invalid input returns null. */
    public NamespacedKey parse(String value) {
        if (value == null || value.isEmpty()) return null;
        int separator = value.indexOf(':');
        String qualified = separator < 0 ? namespace + ":" + value : separator == 0 ? namespace + value : value;
        return NamespacedKey.fromString(qualified);
    }
}
