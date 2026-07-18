/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import com.google.gson.annotations.SerializedName;
import io.github.polymcreborn.api.ContentType;

import java.util.Objects;
import java.util.regex.Pattern;

/** One stable persisted assignment. Empty state means the whole registry entry. */
public record StoredMapping(
        @SerializedName("registry_id") String registryId,
        @SerializedName("content_type") ContentType contentType,
        @SerializedName("state") String state,
        String strategy,
        @SerializedName("client_carrier") String clientCarrier,
        @SerializedName("resource_hash") String resourceHash,
        @SerializedName("created_with") String createdWith,
        @SerializedName("last_validated_with") String lastValidatedWith) implements Comparable<StoredMapping> {

    private static final Pattern NAMESPACED_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern STATE = Pattern.compile(
            "[a-z0-9_.-]+=[a-z0-9_.-]+(?:,[a-z0-9_.-]+=[a-z0-9_.-]+)*");
    private static final Pattern STRATEGY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private static final int MAX_ID_LENGTH = 512;
    private static final int MAX_STATE_LENGTH = 4_096;
    private static final int MAX_VERSION_LENGTH = 128;

    public StoredMapping {
        requireNamespacedId(registryId);
        Objects.requireNonNull(contentType, "contentType");
        state = Objects.requireNonNull(state, "state");
        if (state.length() > MAX_STATE_LENGTH || (!state.isEmpty() && !STATE.matcher(state).matches())) {
            throw new IllegalArgumentException("state must be empty or a canonical comma-separated property map");
        }
        if (contentType != ContentType.BLOCK && !state.isEmpty()) {
            throw new IllegalArgumentException("state is only valid for block mappings");
        }
        strategy = requireText(strategy, "strategy");
        if (!STRATEGY.matcher(strategy).matches()) {
            throw new IllegalArgumentException("strategy contains unsupported characters or exceeds 128 bytes");
        }
        clientCarrier = requireCarrier(clientCarrier);
        resourceHash = Objects.requireNonNull(resourceHash, "resourceHash");
        if (!resourceHash.isEmpty() && !resourceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("resource_hash must be empty or lowercase SHA-256");
        }
        createdWith = requireBoundedText(createdWith, "createdWith", MAX_VERSION_LENGTH);
        lastValidatedWith = requireBoundedText(lastValidatedWith, "lastValidatedWith", MAX_VERSION_LENGTH);
    }

    public String key() {
        return contentType.name().toLowerCase() + ":" + registryId + (state.isEmpty() ? "" : "[" + state + "]");
    }

    public StoredMapping validatedWith(String version) {
        return new StoredMapping(registryId, contentType, state, strategy, clientCarrier, resourceHash,
                createdWith, version);
    }

    @Override
    public int compareTo(StoredMapping other) {
        return key().compareTo(other.key());
    }

    private static void requireNamespacedId(String value) {
        value = requireText(value, "registryId");
        if (value.length() > MAX_ID_LENGTH || !NAMESPACED_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("registry_id must be a canonical lowercase namespaced identifier");
        }
    }

    private static String requireCarrier(String value) {
        value = requireText(value, "clientCarrier");
        if (value.length() > MAX_STATE_LENGTH) {
            throw new IllegalArgumentException("client_carrier exceeds the hard length limit");
        }
        if (value.equals("none")) {
            return value;
        }
        int stateStart = value.indexOf('[');
        String identifier = stateStart < 0 ? value : value.substring(0, stateStart);
        requireNamespacedId(identifier);
        if (stateStart >= 0) {
            if (!value.endsWith("]") || stateStart == value.length() - 2
                    || value.indexOf('[', stateStart + 1) >= 0) {
                throw new IllegalArgumentException("client_carrier has malformed block-state syntax");
            }
            String properties = value.substring(stateStart + 1, value.length() - 1);
            if (!STATE.matcher(properties).matches()) {
                throw new IllegalArgumentException("client_carrier has a non-canonical block-state map");
            }
        }
        return value;
    }

    private static String requireBoundedText(String value, String name, int maximumLength) {
        value = requireText(value, name);
        if (value.length() > maximumLength || value.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException(name + " contains control characters or exceeds the hard limit");
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
