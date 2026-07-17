/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Versioned deterministic on-disk document. */
public record MappingStoreDocument(
        @SerializedName("schema_version") int schemaVersion,
        @SerializedName("mapping_algorithm_version") String mappingAlgorithmVersion,
        List<StoredMapping> mappings) {

    public static final int SCHEMA_VERSION = 1;
    public static final String ALGORITHM_VERSION = "reborn-2";

    public MappingStoreDocument {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported mapping schema " + schemaVersion);
        }
        if (!ALGORITHM_VERSION.equals(mappingAlgorithmVersion)) {
            throw new IllegalArgumentException("Unsupported mapping algorithm " + mappingAlgorithmVersion);
        }
        mappings = mappings.stream().sorted().toList();
        for (int index = 1; index < mappings.size(); index++) {
            if (mappings.get(index - 1).key().equals(mappings.get(index).key())) {
                throw new IllegalArgumentException("Duplicate stored mapping " + mappings.get(index).key());
            }
        }
    }

    public static MappingStoreDocument empty() {
        return new MappingStoreDocument(SCHEMA_VERSION, ALGORITHM_VERSION, List.of());
    }
}
