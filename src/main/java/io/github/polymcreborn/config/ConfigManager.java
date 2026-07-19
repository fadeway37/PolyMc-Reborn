/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/** Creates directory layout and strictly reads/writes the main config. */
public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version", "enabled", "generate_resource_pack", "resource_pack_policy", "persistent_mappings", "safe_mode",
            "log_decision_chains", "packet_fallback_enabled", "creative_reverse_mapping_enabled",
            "override_native_polymer", "report_formats", "cache_limits", "resource_extraction_limits");
    private static final Set<String> CACHE_FIELDS = Set.of("max_entries", "max_bytes");
    private static final Set<String> REQUIRED_ROOT_FIELDS = ROOT_FIELDS.stream()
            .filter(field -> !field.equals("resource_pack_policy"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> EXTRACTION_FIELDS = Set.of(
            "max_files", "max_single_file_bytes", "max_total_bytes");

    private final Path root;
    private final Path configFile;

    public ConfigManager(Path fabricConfigDirectory) {
        root = fabricConfigDirectory.resolve("polymc-reborn").toAbsolutePath().normalize();
        configFile = root.resolve("config.json");
    }

    public RebornConfig loadOrCreate() {
        createLayout();
        if (Files.notExists(configFile)) {
            write(RebornConfig.defaults());
        }
        return parse(configFile);
    }

    public RebornConfig validate() {
        return parse(configFile);
    }

    public void write(RebornConfig config) {
        try {
            var json = GSON.toJson(config) + System.lineSeparator();
            AtomicFiles.write(configFile, json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ConfigurationException(configFile, "$", "Unable to atomically write config", exception);
        }
    }

    public Path root() {
        return root;
    }

    public Path compatDirectory() {
        return root.resolve("compat.d");
    }

    public Path reportsDirectory() {
        return root.resolve("reports");
    }

    public Path cacheDirectory() {
        return root.resolve("cache");
    }

    public Path diagnosticsPolicyFile() {
        return root.resolve("diagnostics-policy.json");
    }

    public Path supportDirectory() {
        return root.resolve("support");
    }

    private void createLayout() {
        try {
            Files.createDirectories(compatDirectory());
            Files.createDirectories(reportsDirectory());
            Files.createDirectories(cacheDirectory());
            Files.createDirectories(supportDirectory());
        } catch (IOException exception) {
            throw new ConfigurationException(configFile, "$", "Unable to create configuration layout", exception);
        }
    }

    private static RebornConfig parse(Path file) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new ConfigurationException(file, "$", "Expected a JSON object");
            }
            var root = element.getAsJsonObject();
            rejectUnknown(file, "$", root, ROOT_FIELDS);
            requireFields(file, "$", root, REQUIRED_ROOT_FIELDS);
            var cacheLimits = requireObject(file, root, "cache_limits");
            rejectUnknown(file, "$.cache_limits", cacheLimits, CACHE_FIELDS);
            requireFields(file, "$.cache_limits", cacheLimits, CACHE_FIELDS);
            var extractionLimits = requireObject(file, root, "resource_extraction_limits");
            rejectUnknown(file, "$.resource_extraction_limits",
                    extractionLimits, EXTRACTION_FIELDS);
            requireFields(file, "$.resource_extraction_limits", extractionLimits, EXTRACTION_FIELDS);
            validateTypes(file, root, cacheLimits, extractionLimits);
            try {
                return GSON.fromJson(root, RebornConfig.class);
            } catch (RuntimeException exception) {
                throw new ConfigurationException(file, "$", "Invalid value: " + exception.getMessage(), exception);
            }
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ConfigurationException(file, "$", "Unable to parse JSON: " + exception.getMessage(), exception);
        }
    }

    private static void validateTypes(Path file, JsonObject root, JsonObject cacheLimits,
                                      JsonObject extractionLimits) {
        requireInteger(file, "$.schema_version", root.get("schema_version"), Integer.MIN_VALUE, Integer.MAX_VALUE);
        for (var field : java.util.List.of("enabled", "generate_resource_pack", "persistent_mappings", "safe_mode",
                "log_decision_chains", "packet_fallback_enabled", "creative_reverse_mapping_enabled",
                "override_native_polymer")) {
            requireBoolean(file, "$." + field, root.get(field));
        }
        requireStringArray(file, "$.report_formats", root.get("report_formats"));
        if (root.has("resource_pack_policy")) {
            requireString(file, "$.resource_pack_policy", root.get("resource_pack_policy"));
            String policy = root.get("resource_pack_policy").getAsString();
            if (!java.util.Set.of("REQUIRED", "OPTIONAL", "DISABLED").contains(policy)) {
                throw new ConfigurationException(file, "$.resource_pack_policy",
                        "Expected REQUIRED, OPTIONAL, or DISABLED");
            }
        }
        requireInteger(file, "$.cache_limits.max_entries", cacheLimits.get("max_entries"),
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        requireInteger(file, "$.cache_limits.max_bytes", cacheLimits.get("max_bytes"),
                Long.MIN_VALUE, Long.MAX_VALUE);
        requireInteger(file, "$.resource_extraction_limits.max_files", extractionLimits.get("max_files"),
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        requireInteger(file, "$.resource_extraction_limits.max_single_file_bytes",
                extractionLimits.get("max_single_file_bytes"), Long.MIN_VALUE, Long.MAX_VALUE);
        requireInteger(file, "$.resource_extraction_limits.max_total_bytes",
                extractionLimits.get("max_total_bytes"), Long.MIN_VALUE, Long.MAX_VALUE);
    }

    private static JsonObject requireObject(Path file, JsonObject parent, String name) {
        if (!parent.has(name) || !parent.get(name).isJsonObject()) {
            throw new ConfigurationException(file, "$." + name, "Expected an object");
        }
        return parent.getAsJsonObject(name);
    }

    public static void rejectUnknown(Path file, String path, JsonObject object, Set<String> allowed) {
        for (var field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new ConfigurationException(file, path + "." + field,
                        "Unknown field (unknown fields are errors in every PolyMc Reborn config)");
            }
        }
    }

    public static void requireFields(Path file, String path, JsonObject object, Set<String> required) {
        for (var field : required) {
            if (!object.has(field) || object.get(field).isJsonNull()) {
                throw new ConfigurationException(file, path + "." + field, "Required field is missing");
            }
        }
    }

    static void requireBoolean(Path file, String path, JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new ConfigurationException(file, path, "Expected a JSON boolean");
        }
    }

    static void requireString(Path file, String path, JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new ConfigurationException(file, path, "Expected a JSON string");
        }
    }

    static void requireInteger(Path file, String path, JsonElement value, long minimum, long maximum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ConfigurationException(file, path, "Expected a JSON integer");
        }
        try {
            BigInteger integer = value.getAsBigDecimal().toBigIntegerExact();
            if (integer.compareTo(BigInteger.valueOf(minimum)) < 0
                    || integer.compareTo(BigInteger.valueOf(maximum)) > 0) {
                throw new ArithmeticException("outside target integer range");
            }
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ConfigurationException(file, path, "Expected a JSON integer in range", exception);
        }
    }

    static void requireStringArray(Path file, String path, JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            throw new ConfigurationException(file, path, "Expected an array of JSON strings");
        }
        int index = 0;
        for (var element : value.getAsJsonArray()) {
            requireString(file, path + "[" + index + "]", element);
            index++;
        }
    }
}
