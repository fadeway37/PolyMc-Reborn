/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import com.google.gson.GsonBuilder;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.core.PolyMcReborn;
import io.github.polymcreborn.core.RebornRuntime;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Creates a bounded, local-only, redacted and atomically replaced support ZIP. */
public final class SupportBundleService {
    private static final long MAX_SOURCE_BYTES = 2_097_152L;
    private static final long MAX_BUNDLE_BYTES = 8_388_608L;
    private static final int MAX_ENTRIES = 20;
    private static final Pattern WINDOWS_PATH = Pattern.compile(
            "(?i)(?:[a-z]:\\\\|/(?:home|users|root|private|tmp)/)[^\\r\\n\\\"']+?"
                    + "(?=\\s+\\\"?(?:token|secret|password|authorization|hmac[_-]?key)"
                    + "\\\"?\\s*[:=]|[\\r\\n\\\"']|$)");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\\"?(?:token|secret|password|authorization|hmac[_-]?key)\\\"?"
                    + "\\s*[:=]\\s*\\\"?[^,\\s\\\"}]+\\\"?");
    private final RebornRuntime runtime;
    private volatile SupportBundleResult lastResult;

    public SupportBundleService(RebornRuntime runtime) {
        this.runtime = java.util.Objects.requireNonNull(runtime, "runtime");
    }

    public synchronized SupportBundleResult build() {
        runtime.writeReports();
        var entries = new TreeMap<String, byte[]>();
        int[] redactions = {0};
        addText(entries, "sanitized/config.json", sanitizedConfig(), redactions);
        addBoundedFile(entries, "diagnostics/diagnostics-policy.json",
                runtime.configManager().diagnosticsPolicyFile(), redactions);
        addBoundedFile(entries, "reports/compatibility-latest.json",
                runtime.configManager().reportsDirectory().resolve("compatibility-latest.json"), redactions);
        addBoundedFile(entries, "reports/compatibility-latest.md",
                runtime.configManager().reportsDirectory().resolve("compatibility-latest.md"), redactions);
        addBoundedFile(entries, "reports/resource-pack-latest.json",
                runtime.configManager().reportsDirectory().resolve("resource-pack-latest.json"), redactions);
        addBoundedFile(entries, "reports/resource-pack-latest.md",
                runtime.configManager().reportsDirectory().resolve("resource-pack-latest.md"), redactions);
        addText(entries, "runtime/summary.json", runtimeSummary(), redactions);
        addText(entries, "redaction-report.json", "{\n  \"schema_version\": 1,\n"
                + "  \"redactions\": " + redactions[0] + ",\n"
                + "  \"excluded\": [\"worlds\", \"player-identifiers\", \"addresses\", "
                + "\"credentials\", \"keys\", \"mod-jars\", \"environment\", \"raw-logs\"]\n}\n",
                redactions);
        addText(entries, "manifest.json", manifest(entries), redactions);
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalStateException("Support bundle entry capacity exceeded");
        }
        byte[] zip = zip(entries);
        if (zip.length > MAX_BUNDLE_BYTES) {
            throw new IllegalStateException("Support bundle exceeds the 8 MiB output limit");
        }
        Path output = runtime.configManager().supportDirectory().resolve("polymc-reborn-support.zip");
        try {
            AtomicFiles.write(output, zip);
        } catch (IOException exception) {
            throw new IllegalStateException("Support bundle atomic write failed", exception);
        }
        lastResult = new SupportBundleResult(output.getFileName().toString(), sha256(zip), zip.length,
                entries.size(), redactions[0]);
        return lastResult;
    }

    public SupportBundleResult status() {
        return lastResult;
    }

    static String redactText(String input, int[] count) {
        String output = replace(input, WINDOWS_PATH, "<redacted-path>", count);
        output = replace(output, SECRET_ASSIGNMENT, "<redacted-secret>", count);
        return output;
    }

    private String sanitizedConfig() {
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(runtime.config()) + "\n";
    }

    private String runtimeSummary() {
        var root = new LinkedHashMap<String, Object>();
        root.put("schema_version", 1);
        root.put("polymc_reborn_version", PolyMcReborn.VERSION);
        root.put("minecraft_version", "26.1.2");
        root.put("java_version", Runtime.version().feature());
        root.put("mapping_schema", runtime.mappingSnapshot().schemaVersion());
        root.put("mapping_algorithm", runtime.mappingSnapshot().mappingAlgorithmVersion());
        root.put("mapping_entries", runtime.mappingSnapshot().mappings().size());
        root.put("pack_policy", runtime.config().resourcePackPolicy());
        root.put("pack_stats", runtime.playerPackStates().stats());
        root.put("gui_active_sessions", runtime.guiProjectionService().activeSessionCount());
        root.put("entity_active_sessions", runtime.entityProjectionBackend().activeSessionCount());
        root.put("mods", FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId() + "@"
                        + mod.getMetadata().getVersion().getFriendlyString()).sorted().toList());
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static String manifest(Map<String, byte[]> entries) {
        var list = new ArrayList<Map<String, Object>>();
        entries.forEach((name, bytes) -> list.add(Map.of(
                "name", name, "bytes", bytes.length, "sha256", sha256(bytes))));
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(Map.of(
                "schema_version", 1, "entries", list,
                "privacy", "local-only; no automatic upload")) + "\n";
    }

    private static void addBoundedFile(Map<String, byte[]> entries, String name, Path source,
                                       int[] redactions) {
        try {
            if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            long size = Files.size(source);
            if (size < 0 || size > MAX_SOURCE_BYTES) {
                throw new IllegalStateException("Support source exceeds 2 MiB limit: " + name);
            }
            addText(entries, name, Files.readString(source, StandardCharsets.UTF_8), redactions);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bounded support source " + name, exception);
        }
    }

    private static void addText(Map<String, byte[]> entries, String name, String text, int[] redactions) {
        if (!name.matches("[a-z0-9._/-]+") || name.contains("..") || name.startsWith("/")) {
            throw new IllegalArgumentException("Unsafe support entry name: " + name);
        }
        byte[] bytes = redactText(text, redactions).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_SOURCE_BYTES || entries.putIfAbsent(name, bytes) != null) {
            throw new IllegalStateException("Support entry is oversized or duplicated: " + name);
        }
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        try {
            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                for (var entry : entries.entrySet()) {
                    var zipEntry = new ZipEntry(entry.getKey());
                    zipEntry.setTime(0L);
                    zip.putNextEntry(zipEntry);
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Support ZIP construction failed", exception);
        }
    }

    private static String replace(String input, Pattern pattern, String replacement, int[] count) {
        var matcher = pattern.matcher(input);
        var output = new StringBuffer();
        while (matcher.find()) {
            count[0]++;
            matcher.appendReplacement(output, replacement);
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
