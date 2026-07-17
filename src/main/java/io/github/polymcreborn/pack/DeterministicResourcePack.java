/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.polymcreborn.api.ResourceContributor;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.config.RebornConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bounded, path-safe, conflict-strict and byte-deterministic resource pack builder. */
public final class DeterministicResourcePack implements ResourceContributor.ResourceSink {
    private static final long ZIP_EPOCH = 0L;
    private static final int MAX_MODEL_PARENT_DEPTH = 64;
    private final RebornConfig.ResourceExtractionLimits limits;
    private final TreeMap<String, Entry> entries = new TreeMap<>();
    private final List<String> duplicates = new ArrayList<>();
    private long totalBytes;

    public DeterministicResourcePack(RebornConfig.ResourceExtractionLimits limits) {
        this.limits = limits;
    }

    @Override
    public synchronized void put(String relativePath, byte[] data, String source) {
        String normalized = normalize(relativePath);
        String logicalSource = sanitizeSource(source);
        byte[] immutable = Arrays.copyOf(data, data.length);
        if (immutable.length > limits.maxSingleFileBytes()) {
            throw new ResourcePackException("Resource exceeds max_single_file_bytes: " + normalized);
        }
        var previous = entries.get(normalized);
        if (previous != null) {
            if (Arrays.equals(previous.data(), immutable)) {
                duplicates.add(normalized + " (identical: " + previous.source() + ", " + logicalSource + ")");
                return;
            }
            throw new ResourcePackException("Conflicting resources at " + normalized + " from "
                    + previous.source() + " and " + logicalSource);
        }
        if (entries.size() >= limits.maxFiles()) {
            throw new ResourcePackException("Resource count exceeds max_files=" + limits.maxFiles());
        }
        if (totalBytes + immutable.length > limits.maxTotalBytes()) {
            throw new ResourcePackException("Resources exceed max_total_bytes=" + limits.maxTotalBytes());
        }
        entries.put(normalized, new Entry(immutable, logicalSource));
        totalBytes += immutable.length;
    }

    /** Checks the bounded size before allocation, then delegates to the conflict-strict in-memory store. */
    public void putFile(String relativePath, Path file, String source) throws IOException {
        long size = Files.size(file);
        if (size > limits.maxSingleFileBytes()) {
            throw new ResourcePackException("Resource exceeds max_single_file_bytes: " + normalize(relativePath));
        }
        if (size > Integer.MAX_VALUE) {
            throw new ResourcePackException("Resource cannot be represented safely in memory: "
                    + normalize(relativePath));
        }
        put(relativePath, Files.readAllBytes(file), source);
    }

    public synchronized List<String> duplicates() {
        return duplicates.stream().sorted().toList();
    }

    public synchronized List<String> validateModelDependencies() {
        Set<String> missing = new LinkedHashSet<>();
        var parentGraph = new TreeMap<String, String>();
        for (var entry : entries.entrySet()) {
            String path = entry.getKey();
            if (!path.contains("/models/") || !path.endsWith(".json")) {
                continue;
            }
            try {
                var model = JsonParser.parseString(new String(entry.getValue().data(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                String namespace = namespaceFromAssetPath(path);
                if (model.has("parent") && model.get("parent").isJsonPrimitive()) {
                    String dependency = modelPath(namespace, model.get("parent").getAsString());
                    if (!dependency.startsWith("assets/minecraft/") && !entries.containsKey(dependency)) {
                        missing.add(dependency + " referenced by " + path);
                    } else if (!dependency.startsWith("assets/minecraft/")) {
                        parentGraph.put(path, dependency);
                    }
                }
                if (model.has("textures") && model.get("textures").isJsonObject()) {
                    for (var texture : model.getAsJsonObject("textures").entrySet()) {
                        if (!texture.getValue().isJsonPrimitive()) {
                            continue;
                        }
                        String value = texture.getValue().getAsString();
                        if (value.startsWith("#")) {
                            continue;
                        }
                        String dependency = texturePath(namespace, value);
                        if (!dependency.startsWith("assets/minecraft/") && !entries.containsKey(dependency)) {
                            missing.add(dependency + " referenced by " + path);
                        }
                    }
                }
            } catch (RuntimeException exception) {
                missing.add("invalid model JSON " + path + ": " + exception.getMessage());
            }
        }
        for (var start : parentGraph.keySet()) {
            var visited = new LinkedHashSet<String>();
            String current = start;
            int depth = 0;
            while (current != null) {
                if (!visited.add(current)) {
                    missing.add("model parent cycle: " + String.join(" -> ", visited) + " -> " + current);
                    break;
                }
                if (++depth > MAX_MODEL_PARENT_DEPTH) {
                    missing.add("model parent depth exceeds " + MAX_MODEL_PARENT_DEPTH + " at " + start);
                    break;
                }
                current = parentGraph.get(current);
            }
        }
        return List.copyOf(missing);
    }

    public synchronized PackBuildResult build(Path output) {
        var warnings = validateModelDependencies();
        ensurePackMetadata();
        addManifest();
        byte[] archive = zipBytes();
        String hash = sha256(archive);
        try {
            AtomicFiles.write(output, archive);
        } catch (IOException exception) {
            throw new ResourcePackException("Unable to atomically write resource pack", exception);
        }
        return new PackBuildResult(hash, entries.size(), totalBytes, archive.length, false,
                duplicates.stream().sorted().toList(), warnings.stream().sorted().toList());
    }

    public synchronized byte[] buildBytes() {
        ensurePackMetadata();
        addManifest();
        return zipBytes();
    }

    public synchronized Map<String, byte[]> snapshot() {
        var output = new TreeMap<String, byte[]>();
        entries.forEach((path, entry) -> output.put(path, Arrays.copyOf(entry.data(), entry.data().length)));
        return Map.copyOf(output);
    }

    public static String normalize(String path) {
        if (path == null || path.isBlank() || path.length() > 1024 || path.indexOf('\0') >= 0
                || path.indexOf('\\') >= 0 || path.startsWith("/") || path.contains(":")) {
            throw new ResourcePackException("Unsafe resource path: " + path);
        }
        var segments = path.split("/", -1);
        var normalized = new ArrayList<String>(segments.length);
        for (var segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ResourcePackException("Unsafe resource path segment in " + path);
            }
            normalized.add(segment);
        }
        return String.join("/", normalized);
    }

    private void ensurePackMetadata() {
        if (!entries.containsKey("pack.mcmeta")) {
            put("pack.mcmeta", ("{\"pack\":{\"description\":\"PolyMc Reborn generated resources\","
                    + "\"min_format\":84,\"max_format\":84}}\n")
                    .getBytes(StandardCharsets.UTF_8), "polymc-reborn");
        }
    }

    private void addManifest() {
        if (entries.containsKey("polymc-reborn-manifest.json")) {
            return;
        }
        var manifest = new JsonObject();
        manifest.addProperty("schema_version", 1);
        var resources = new com.google.gson.JsonArray();
        entries.forEach((path, entry) -> {
            var resource = new JsonObject();
            resource.addProperty("path", path);
            resource.addProperty("sha256", sha256(entry.data()));
            resource.addProperty("size", entry.data().length);
            resource.addProperty("source", entry.source());
            resources.add(resource);
        });
        manifest.add("resources", resources);
        var bytes = (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(manifest) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        put("polymc-reborn-manifest.json", bytes, "polymc-reborn");
    }

    private byte[] zipBytes() {
        try {
            var output = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                zip.setLevel(9);
                for (var resource : entries.entrySet()) {
                    byte[] data = resource.getValue().data();
                    var crc = new CRC32();
                    crc.update(data);
                    var zipEntry = new ZipEntry(resource.getKey());
                    zipEntry.setTime(ZIP_EPOCH);
                    zipEntry.setMethod(ZipEntry.STORED);
                    zipEntry.setSize(data.length);
                    zipEntry.setCompressedSize(data.length);
                    zipEntry.setCrc(crc.getValue());
                    zip.putNextEntry(zipEntry);
                    zip.write(data);
                    zip.closeEntry();
                }
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new ResourcePackException("In-memory ZIP construction failed", exception);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM has no SHA-256", impossible);
        }
    }

    private static String namespaceFromAssetPath(String path) {
        var parts = path.split("/", 4);
        return parts.length > 1 ? parts[1] : "minecraft";
    }

    private static String modelPath(String defaultNamespace, String identifier) {
        String[] parts = splitIdentifier(defaultNamespace, identifier);
        return "assets/" + parts[0] + "/models/" + parts[1] + ".json";
    }

    private static String texturePath(String defaultNamespace, String identifier) {
        String[] parts = splitIdentifier(defaultNamespace, identifier);
        return "assets/" + parts[0] + "/textures/" + parts[1] + ".png";
    }

    private static String[] splitIdentifier(String defaultNamespace, String identifier) {
        int separator = identifier.indexOf(':');
        return separator >= 0
                ? new String[]{identifier.substring(0, separator), identifier.substring(separator + 1)}
                : new String[]{defaultNamespace, identifier};
    }

    private static String sanitizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "unknown";
        }
        String normalized = source.replace('\\', '/');
        int separator = normalized.lastIndexOf('/');
        return separator >= 0 ? normalized.substring(separator + 1) : normalized;
    }

    private record Entry(byte[] data, String source) {
    }
}
