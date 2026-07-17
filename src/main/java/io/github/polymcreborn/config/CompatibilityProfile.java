/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import com.google.gson.annotations.SerializedName;
import io.github.polymcreborn.api.ContentDescriptor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict declarative compatibility profile; it contains data only and never executable code. */
public record CompatibilityProfile(
        @SerializedName("schema_version") int schemaVersion,
        String id,
        @SerializedName("target_mod") String targetMod,
        @SerializedName("target_version") String targetVersion,
        @SerializedName("optional_dependencies") List<String> optionalDependencies,
        int priority,
        String description,
        List<Rule> rules) {

    public static final int SCHEMA_VERSION = 1;

    public CompatibilityProfile {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported compat schema_version " + schemaVersion);
        }
        id = requireToken(id, "id");
        targetMod = requireToken(targetMod, "target_mod");
        targetVersion = requireText(targetVersion, "target_version");
        optionalDependencies = optionalDependencies.stream().map(value -> requireToken(value, "optional dependency"))
                .sorted().distinct().toList();
        if (priority < -10_000 || priority > 10_000) {
            throw new IllegalArgumentException("priority is outside -10000..10000");
        }
        description = requireText(description, "description");
        rules = List.copyOf(rules);
        if (rules.isEmpty()) {
            throw new IllegalArgumentException("rules must not be empty");
        }
    }

    public record Rule(Match match, Action action) {
        public Rule {
            Objects.requireNonNull(match, "match");
            Objects.requireNonNull(action, "action");
        }

        public boolean matches(ContentDescriptor descriptor) {
            return match.matches(descriptor);
        }
    }

    public record Match(
            @SerializedName("exact_id") String exactId,
            String namespace,
            String glob,
            @SerializedName("registry_type") String registryType,
            @SerializedName("block_properties") Map<String, String> blockProperties,
            @SerializedName("owner_mod") String ownerMod) {

        public Match {
            blockProperties = blockProperties == null ? Map.of() : Map.copyOf(blockProperties);
            if (exactId == null && namespace == null && glob == null && registryType == null
                    && blockProperties.isEmpty() && ownerMod == null) {
                throw new IllegalArgumentException("A compat match must constrain at least one field");
            }
            if (glob != null) {
                SafeGlob.compile(glob);
            }
        }

        public boolean matches(ContentDescriptor descriptor) {
            if (exactId != null && !exactId.equals(descriptor.registryId())) {
                return false;
            }
            if (namespace != null && !descriptor.registryId().startsWith(namespace + ":")) {
                return false;
            }
            if (glob != null && !SafeGlob.compile(glob).matches(descriptor.registryId())) {
                return false;
            }
            if (registryType != null && !registryType.equalsIgnoreCase(descriptor.contentType().name())) {
                return false;
            }
            if (ownerMod != null && !ownerMod.equals(descriptor.ownerMod())) {
                return false;
            }
            for (var property : blockProperties.entrySet()) {
                String available = descriptor.attributes().get("block_property." + property.getKey());
                if (available == null || !property.getValue().equals("*")
                        && java.util.Arrays.stream(available.split(",", -1))
                        .noneMatch(property.getValue()::equals)) {
                    return false;
                }
            }
            return true;
        }
    }

    public record Action(
            String type,
            String value,
            @SerializedName("override_native_polymer") boolean overrideNativePolymer) {
        private static final Set<String> ALLOWED = Set.of(
                "disable_auto_mapping", "item_carrier_category", "block_strategy",
                "vanilla_fallback_state", "entity_replacement", "gui_classification");
        private static final Set<String> ITEM_CATEGORIES = Set.of(
                "food", "drink", "tool", "armor", "bow", "crossbow", "shield",
                "throwable", "block_item", "material");

        public Action {
            type = requireToken(type, "action.type");
            if (!ALLOWED.contains(type)) {
                throw new IllegalArgumentException("Unknown compat action type: " + type);
            }
            value = value == null ? "" : value;
            if (!type.equals("disable_auto_mapping") && value.isBlank()) {
                throw new IllegalArgumentException("action.value is required for " + type);
            }
            switch (type) {
                case "disable_auto_mapping" -> {
                    if (!value.isEmpty()) {
                        throw new IllegalArgumentException("disable_auto_mapping requires an empty value");
                    }
                }
                case "item_carrier_category" -> {
                    if (!ITEM_CATEGORIES.contains(value)) {
                        throw new IllegalArgumentException("Unknown item carrier category " + value);
                    }
                }
                case "block_strategy" -> {
                    if (!value.equals("textured-full-cube")) {
                        throw new IllegalArgumentException("0.1 only supports block_strategy=textured-full-cube");
                    }
                }
                case "vanilla_fallback_state" -> requireVanillaValue(value, true, type);
                case "entity_replacement" -> requireVanillaValue(value, false, type);
                case "gui_classification" -> {
                    requireToken(value, "action.value");
                    if (value.length() > 128) {
                        throw new IllegalArgumentException("gui_classification is longer than 128 characters");
                    }
                }
                default -> throw new IllegalStateException("Validated action became unknown: " + type);
            }
        }
    }

    private static void requireVanillaValue(String value, boolean allowState, String name) {
        if (!value.startsWith("minecraft:") || value.length() > 256) {
            throw new IllegalArgumentException(name + " must name a bounded vanilla value");
        }
        for (int index = "minecraft:".length(); index < value.length(); index++) {
            char character = value.charAt(index);
            boolean identifier = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '-' || character == '.' || character == '/';
            boolean stateSyntax = allowState && (character == '[' || character == ']'
                    || character == '=' || character == ',');
            if (!identifier && !stateSyntax) {
                throw new IllegalArgumentException(name + " contains an unsafe character");
            }
        }
        if (value.length() == "minecraft:".length()
                || (!allowState && (value.indexOf('[') >= 0 || value.indexOf(']') >= 0))) {
            throw new IllegalArgumentException(name + " must name a vanilla registry entry");
        }
        if (allowState) {
            int open = value.indexOf('[');
            int close = value.indexOf(']');
            if ((open < 0) != (close < 0) || close >= 0 && close != value.length() - 1
                    || open >= 0 && value.indexOf('[', open + 1) >= 0
                    || close >= 0 && value.indexOf(']', close + 1) >= 0) {
                throw new IllegalArgumentException(name + " has invalid block-state brackets");
            }
            if (open >= 0) {
                String properties = value.substring(open + 1, close);
                if (properties.isBlank()) {
                    throw new IllegalArgumentException(name + " has an empty block-state property list");
                }
                for (var pair : properties.split(",", -1)) {
                    int equals = pair.indexOf('=');
                    if (equals <= 0 || equals == pair.length() - 1 || pair.indexOf('=', equals + 1) >= 0) {
                        throw new IllegalArgumentException(name + " has an invalid block-state property");
                    }
                }
            }
        }
    }

    private static String requireToken(String value, String name) {
        value = requireText(value, name);
        if (value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must not contain whitespace");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
