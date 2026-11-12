package org.blu3fishez.springpaper.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import org.gradle.api.GradleException;

/** Secret values live only in memory until passed to the owned native child process. */
final class ForwardingSecret {
    private ForwardingSecret() {}
    static String resolve(NativeConfiguration.Plan plan, Map<String, String> environment) throws IOException {
        if (!plan.requiresSecret()) return null;
        String nativeValue = environment.getOrDefault(plan.secretVariable(), "");
        boolean nativeEffective = plan.secretVariable().equals("PAPER_VELOCITY_SECRET") ? !nativeValue.isEmpty() : !nativeValue.isBlank();
        String source = null;
        if (plan.secretSource() != null && Files.exists(plan.secretSource())) {
            if (!Files.isRegularFile(plan.secretSource())) throw new GradleException("Forwarding secret source must be an existing regular file (value redacted)");
            source = String.join("", Files.readAllLines(plan.secretSource()));
            if (source.isBlank()) throw new GradleException("Forwarding secret source is empty (value redacted)");
        } else if (plan.secretSource() != null && !nativeEffective) {
            throw new GradleException("Forwarding secret source does not exist; provide an existing file or the native environment variable (value redacted). No secret is generated.");
        }
        if (nativeEffective && source != null && !nativeValue.equals(source)) {
            throw new GradleException("Conflicting effective native forwarding secret and configured file source (values redacted)");
        }
        if (nativeEffective) return nativeValue;
        if (source != null) return source;
        throw new GradleException("Forwarding requires an existing secret-file or native environment variable (value redacted). No secret is generated.");
    }
}
