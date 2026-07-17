/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Loads built-in and operator profiles in stable order with a uniform unknown-field error policy. */
public final class CompatProfileLoader {
    private static final Gson GSON = new GsonBuilder().create();
    private static final Set<String> PROFILE_FIELDS = Set.of(
            "schema_version", "id", "target_mod", "target_version", "optional_dependencies",
            "priority", "description", "rules");
    private static final Set<String> RULE_FIELDS = Set.of("match", "action");
    private static final Set<String> MATCH_FIELDS = Set.of(
            "exact_id", "namespace", "glob", "registry_type", "block_properties", "owner_mod");
    private static final Set<String> ACTION_FIELDS = Set.of("type", "value", "override_native_polymer");

    public List<CompatibilityProfile> load(Path operatorDirectory) {
        var profiles = new ArrayList<CompatibilityProfile>();
        loadBuiltIn(profiles);
        try {
            if (Files.isDirectory(operatorDirectory)) {
                try (var paths = Files.list(operatorDirectory)) {
                    for (var path : paths.filter(file -> file.getFileName().toString().endsWith(".json"))
                            .sorted().toList()) {
                        profiles.add(parse(path));
                    }
                }
            }
        } catch (IOException exception) {
            throw new ConfigurationException(operatorDirectory, "$", "Unable to list compat.d", exception);
        }
        var ids = new HashSet<String>();
        for (var profile : profiles) {
            if (!ids.add(profile.id())) {
                throw new ConfigurationException(operatorDirectory, "$", "Duplicate compatibility profile id " + profile.id());
            }
        }
        return profiles.stream()
                .sorted(Comparator.comparingInt(CompatibilityProfile::priority).reversed()
                        .thenComparing(CompatibilityProfile::id))
                .toList();
    }

    public CompatibilityProfile parse(Path file) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parseObject(file, JsonParser.parseReader(reader).getAsJsonObject());
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new ConfigurationException(file, "$", "Invalid compatibility profile: " + exception.getMessage(), exception);
        }
    }

    private void loadBuiltIn(List<CompatibilityProfile> profiles) {
        var resource = CompatProfileLoader.class.getResourceAsStream(
                "/polymc-reborn/compat/builtin-safety.json");
        if (resource == null) {
            throw new IllegalStateException("Missing built-in compatibility profile");
        }
        var pseudoPath = Path.of("builtin", "builtin-safety.json");
        try (resource; var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            profiles.add(parseObject(pseudoPath, JsonParser.parseReader(reader).getAsJsonObject()));
        } catch (IOException | RuntimeException exception) {
            throw new ConfigurationException(pseudoPath, "$", "Invalid built-in profile", exception);
        }
    }

    private CompatibilityProfile parseObject(Path file, JsonObject root) {
        ConfigManager.rejectUnknown(file, "$", root, PROFILE_FIELDS);
        ConfigManager.requireFields(file, "$", root, PROFILE_FIELDS);
        ConfigManager.requireInteger(file, "$.schema_version", root.get("schema_version"),
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        ConfigManager.requireString(file, "$.id", root.get("id"));
        ConfigManager.requireString(file, "$.target_mod", root.get("target_mod"));
        ConfigManager.requireString(file, "$.target_version", root.get("target_version"));
        ConfigManager.requireStringArray(file, "$.optional_dependencies", root.get("optional_dependencies"));
        ConfigManager.requireInteger(file, "$.priority", root.get("priority"),
                Integer.MIN_VALUE, Integer.MAX_VALUE);
        ConfigManager.requireString(file, "$.description", root.get("description"));
        if (!root.get("rules").isJsonArray()) {
            throw new ConfigurationException(file, "$.rules", "Expected an array");
        }
        int ruleIndex = 0;
        for (var ruleElement : root.getAsJsonArray("rules")) {
            if (!ruleElement.isJsonObject()) {
                throw new ConfigurationException(file, "$.rules[" + ruleIndex + "]", "Expected an object");
            }
            var rule = ruleElement.getAsJsonObject();
            ConfigManager.rejectUnknown(file, "$.rules[" + ruleIndex + "]", rule, RULE_FIELDS);
            ConfigManager.requireFields(file, "$.rules[" + ruleIndex + "]", rule, RULE_FIELDS);
            if (!rule.get("match").isJsonObject() || !rule.get("action").isJsonObject()) {
                throw new ConfigurationException(file, "$.rules[" + ruleIndex + "]", "match and action must be objects");
            }
            var match = rule.getAsJsonObject("match");
            var action = rule.getAsJsonObject("action");
            ConfigManager.rejectUnknown(file, "$.rules[" + ruleIndex + "].match", match, MATCH_FIELDS);
            ConfigManager.rejectUnknown(file, "$.rules[" + ruleIndex + "].action", action, ACTION_FIELDS);
            ConfigManager.requireFields(file, "$.rules[" + ruleIndex + "].action", action, ACTION_FIELDS);
            validateMatchTypes(file, ruleIndex, match);
            ConfigManager.requireString(file, "$.rules[" + ruleIndex + "].action.type", action.get("type"));
            ConfigManager.requireString(file, "$.rules[" + ruleIndex + "].action.value", action.get("value"));
            ConfigManager.requireBoolean(file, "$.rules[" + ruleIndex + "].action.override_native_polymer",
                    action.get("override_native_polymer"));
            ruleIndex++;
        }
        try {
            return GSON.fromJson(root, CompatibilityProfile.class);
        } catch (RuntimeException exception) {
            throw new ConfigurationException(file, "$", "Invalid value: " + exception.getMessage(), exception);
        }
    }

    private static void validateMatchTypes(Path file, int ruleIndex, JsonObject match) {
        String path = "$.rules[" + ruleIndex + "].match";
        for (var field : List.of("exact_id", "namespace", "glob", "registry_type", "owner_mod")) {
            if (match.has(field)) {
                ConfigManager.requireString(file, path + "." + field, match.get(field));
            }
        }
        if (!match.has("block_properties")) {
            return;
        }
        if (!match.get("block_properties").isJsonObject()) {
            throw new ConfigurationException(file, path + ".block_properties", "Expected a JSON object");
        }
        var properties = match.getAsJsonObject("block_properties");
        for (var property : properties.entrySet()) {
            ConfigManager.requireString(file, path + ".block_properties." + property.getKey(), property.getValue());
        }
    }
}
