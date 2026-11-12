package org.blu3fishez.springpaper.gradle;

import groovy.json.JsonOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reviewable proposed values, never an instruction to apply changes to unmanaged native files. */
final class NativePlanReport {
    private NativePlanReport() {}
    static Path proposal(Path plan) { return plan.resolveSibling("native-proposal.json"); }

    static void write(Path report, String host, String status, NativeConfiguration.Plan plan) throws IOException {
        Path proposal = proposal(report);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("host", host);
        document.put("status", status);
        List<Map<String, Object>> targets = new ArrayList<>();
        StringBuilder summary = new StringBuilder("host=" + host + "\nstatus=" + status + "\n");
        summary.append("proposal=").append(proposal.toAbsolutePath().normalize()).append('\n');
        for (var entry : plan.files().entrySet()) {
            String hash = OwnedRunDirectory.hash(entry.getValue().getBytes(StandardCharsets.UTF_8));
            Map<String, Object> target = new LinkedHashMap<>();
            target.put("path", entry.getKey().toAbsolutePath().normalize().toString());
            target.put("sha256", hash);
            try { target.put("proposed", redact(NativeFileSemantics.parse(host, entry.getValue()))); }
            catch (RuntimeException invalidNativeSyntax) { target.put("proposed", "<not displayed: invalid native syntax>"); }
            targets.add(target);
            summary.append("file=").append(target.get("path")).append(" sha256=").append(hash).append('\n');
        }
        document.put("targets", targets);
        Map<String, Object> forwarding = new LinkedHashMap<>();
        forwarding.put("secret-required", plan.requiresSecret());
        forwarding.put("secret-source", plan.secretSource() == null ? "not configured" : plan.secretSource().toString());
        forwarding.put("native-environment", plan.secretVariable() == null ? "not used" : plan.secretVariable());
        forwarding.put("policy", "At owned launch, existing file contents are passed only to the owned native child process environment. "
                + "Effective native environment overrides must match any existing configured secret file. "
                + "This proposal resolves no secret value; no secret is generated or rotated.");
        document.put("forwarding", forwarding);
        if (status.equals("plan-only")) summary.append("Existing unmanaged files and external targets remain unchanged. Choose a new empty development.directory before run.\n");
        Files.createDirectories(report.toAbsolutePath().getParent());
        Files.writeString(proposal, JsonOutput.prettyPrint(JsonOutput.toJson(document)) + "\n");
        Files.writeString(report, summary);
    }

    private static Object redact(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key.toString(), sensitive(key.toString()) ? "<redacted>" : redact(item)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(NativePlanReport::redact).toList();
        return value;
    }

    private static boolean sensitive(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (normalized.equals("secretfile") || normalized.equals("forwardingsecretfile")) return false;
        return normalized.contains("secret") || normalized.contains("password") || normalized.contains("passwd")
                || normalized.contains("token") || normalized.contains("credential") || normalized.equals("key")
                || normalized.endsWith("privatekey") || normalized.endsWith("apikey") || normalized.endsWith("accesskey");
    }
}
