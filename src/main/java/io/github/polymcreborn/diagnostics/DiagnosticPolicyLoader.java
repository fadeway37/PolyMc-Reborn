/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.polymcreborn.api.DiagnosticCollector;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.config.ConfigurationException;
import io.github.polymcreborn.config.ConfigManager;
import io.github.polymcreborn.config.SafeGlob;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Strict decoder for diagnostics-policy.json. */
public final class DiagnosticPolicyLoader {
    private static final Set<String> ROOT = Set.of("schema_version", "rules");
    private static final Set<String> RULE = Set.of("id", "code", "registry_id", "mod_id",
            "content_type", "provider_id", "adapter_id", "mapping_status", "client_profile",
            "pack_status", "decision_id", "effective_severity", "reason", "operator_note",
            "known_issue");
    private static final Set<String> REQUIRED_RULE = Set.of("id", "code", "effective_severity",
            "reason", "operator_note", "known_issue");
    private static final byte[] DEFAULT = ("{\n  \"schema_version\": 1,\n  \"rules\": []\n}\n")
            .getBytes(StandardCharsets.UTF_8);

    public DiagnosticPolicy loadOrCreate(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().normalize().getParent());
            if (Files.notExists(file)) {
                AtomicFiles.write(file, DEFAULT);
            }
            return parse(file);
        } catch (java.io.IOException exception) {
            throw new ConfigurationException(file, "$", "Unable to create diagnostic policy", exception);
        }
    }

    public DiagnosticPolicy parse(Path file) {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var element = JsonParser.parseReader(reader);
            if (!element.isJsonObject()) {
                throw new ConfigurationException(file, "$", "Expected a JSON object");
            }
            JsonObject root = element.getAsJsonObject();
            ConfigManager.rejectUnknown(file, "$", root, ROOT);
            ConfigManager.requireFields(file, "$", root, ROOT);
            int schema = root.get("schema_version").getAsInt();
            if (schema != 1 || !root.get("rules").isJsonArray()) {
                throw new ConfigurationException(file, "$", "Expected schema_version 1 and a rules array");
            }
            var rules = new ArrayList<DiagnosticPolicy.Rule>();
            var ids = new HashSet<String>();
            int index = 0;
            for (var elementRule : root.getAsJsonArray("rules")) {
                String path = "$.rules[" + index + "]";
                if (!elementRule.isJsonObject()) {
                    throw new ConfigurationException(file, path, "Expected a JSON object");
                }
                JsonObject rule = elementRule.getAsJsonObject();
                ConfigManager.rejectUnknown(file, path, rule, RULE);
                ConfigManager.requireFields(file, path, rule, REQUIRED_RULE);
                String id = string(file, path + ".id", rule, "id");
                if (!id.matches("[a-z0-9][a-z0-9._-]{0,63}") || !ids.add(id)) {
                    throw new ConfigurationException(file, path + ".id", "Rule id is invalid or duplicated");
                }
                String severityName = string(file, path + ".effective_severity", rule,
                        "effective_severity");
                DiagnosticCollector.Severity severity = switch (severityName) {
                    case "INFO" -> DiagnosticCollector.Severity.INFO;
                    case "WARNING" -> DiagnosticCollector.Severity.WARNING;
                    case "ERROR" -> DiagnosticCollector.Severity.ERROR;
                    default -> throw new ConfigurationException(file, path + ".effective_severity",
                            "Expected INFO, WARNING, or ERROR");
                };
                if (!rule.get("known_issue").isJsonPrimitive()
                        || !rule.getAsJsonPrimitive("known_issue").isBoolean()) {
                    throw new ConfigurationException(file, path + ".known_issue", "Expected a boolean");
                }
                rules.add(new DiagnosticPolicy.Rule(id,
                        SafeGlob.compile(string(file, path + ".code", rule, "code")),
                        glob(file, path, rule, "registry_id"), glob(file, path, rule, "mod_id"),
                        glob(file, path, rule, "content_type"), glob(file, path, rule, "provider_id"),
                        glob(file, path, rule, "adapter_id"), glob(file, path, rule, "mapping_status"),
                        glob(file, path, rule, "client_profile"), glob(file, path, rule, "pack_status"),
                        glob(file, path, rule, "decision_id"),
                        severity, string(file, path + ".reason", rule, "reason"),
                        string(file, path + ".operator_note", rule, "operator_note"),
                        rule.get("known_issue").getAsBoolean()));
                index++;
            }
            return new DiagnosticPolicy(schema, rules, "diagnostics-policy.json");
        } catch (ConfigurationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ConfigurationException(file, "$", "Unable to parse diagnostic policy: "
                    + exception.getMessage(), exception);
        }
    }

    private static String string(Path file, String path, JsonObject object, String field) {
        if (!object.get(field).isJsonPrimitive() || !object.getAsJsonPrimitive(field).isString()) {
            throw new ConfigurationException(file, path, "Expected a string");
        }
        return object.get(field).getAsString();
    }

    private static SafeGlob glob(Path file, String path, JsonObject object, String field) {
        return object.has(field)
                ? SafeGlob.compile(string(file, path + "." + field, object, field))
                : SafeGlob.compile("*");
    }
}
