/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import com.google.gson.annotations.SerializedName;
import io.github.polymcreborn.api.ContentType;

import java.util.Objects;

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

    public StoredMapping {
        requireNamespacedId(registryId);
        Objects.requireNonNull(contentType, "contentType");
        state = Objects.requireNonNull(state, "state");
        strategy = requireText(strategy, "strategy");
        clientCarrier = requireText(clientCarrier, "clientCarrier");
        resourceHash = Objects.requireNonNull(resourceHash, "resourceHash");
        if (!resourceHash.isEmpty() && !resourceHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("resource_hash must be empty or lowercase SHA-256");
        }
        createdWith = requireText(createdWith, "createdWith");
        lastValidatedWith = requireText(lastValidatedWith, "lastValidatedWith");
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
        int separator = value.indexOf(':');
        if (separator < 1 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("registry_id must be namespaced");
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
