/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/** Strict versioned main configuration. Unknown fields are errors. */
public record RebornConfig(
        @SerializedName("schema_version") int schemaVersion,
        boolean enabled,
        @SerializedName("generate_resource_pack") boolean generateResourcePack,
        @SerializedName("persistent_mappings") boolean persistentMappings,
        @SerializedName("safe_mode") boolean safeMode,
        @SerializedName("log_decision_chains") boolean logDecisionChains,
        @SerializedName("packet_fallback_enabled") boolean packetFallbackEnabled,
        @SerializedName("creative_reverse_mapping_enabled") boolean creativeReverseMappingEnabled,
        @SerializedName("override_native_polymer") boolean overrideNativePolymer,
        @SerializedName("report_formats") List<String> reportFormats,
        @SerializedName("cache_limits") CacheLimits cacheLimits,
        @SerializedName("resource_extraction_limits") ResourceExtractionLimits resourceExtractionLimits) {

    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_SINGLE_RESOURCE_BYTES = 268_435_456L;

    public RebornConfig {
        reportFormats = List.copyOf(reportFormats);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported config schema_version " + schemaVersion);
        }
        if (reportFormats.isEmpty() || reportFormats.stream().anyMatch(format ->
                !format.equals("json") && !format.equals("markdown"))) {
            throw new IllegalArgumentException("report_formats must contain json and/or markdown");
        }
        if (cacheLimits == null || resourceExtractionLimits == null) {
            throw new IllegalArgumentException("cache and extraction limits are required");
        }
        cacheLimits.validate();
        resourceExtractionLimits.validate();
    }

    public static RebornConfig defaults() {
        return new RebornConfig(SCHEMA_VERSION, true, true, true, true, true,
                false, false, false, List.of("json", "markdown"),
                new CacheLimits(4096, 67_108_864L),
                new ResourceExtractionLimits(10_000, 8_388_608L, 268_435_456L));
    }

    public record CacheLimits(
            @SerializedName("max_entries") int maxEntries,
            @SerializedName("max_bytes") long maxBytes) {
        void validate() {
            if (maxEntries < 1 || maxEntries > 1_000_000 || maxBytes < 1 || maxBytes > 4_294_967_296L) {
                throw new IllegalArgumentException("cache_limits are outside safe bounds");
            }
        }
    }

    public record ResourceExtractionLimits(
            @SerializedName("max_files") int maxFiles,
            @SerializedName("max_single_file_bytes") long maxSingleFileBytes,
            @SerializedName("max_total_bytes") long maxTotalBytes) {
        void validate() {
            if (maxFiles < 1 || maxFiles > 1_000_000 || maxSingleFileBytes < 1 || maxTotalBytes < 1
                    || maxSingleFileBytes > MAX_SINGLE_RESOURCE_BYTES || maxSingleFileBytes > maxTotalBytes
                    || maxTotalBytes > 8_589_934_592L) {
                throw new IllegalArgumentException("resource_extraction_limits are outside safe bounds");
            }
        }
    }
}
