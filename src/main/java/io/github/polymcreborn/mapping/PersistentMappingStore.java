/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.config.ConfigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict mappings-v1.json store preserving old assignments and producing stable bytes. */
public final class PersistentMappingStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Set<String> ROOT_FIELDS = Set.of(
            "schema_version", "mapping_algorithm_version", "mappings");
    private static final Set<String> ENTRY_FIELDS = Set.of(
            "registry_id", "content_type", "state", "strategy", "client_carrier", "resource_hash",
            "created_with", "last_validated_with");

    private final Path path;

    public PersistentMappingStore(Path polymcConfigRoot) {
        this.path = polymcConfigRoot.resolve("mappings-v1.json").toAbsolutePath().normalize();
    }

    public MappingStoreDocument load() {
        if (Files.notExists(path)) {
            return MappingStoreDocument.empty();
        }
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            var root = JsonParser.parseReader(reader).getAsJsonObject();
            ConfigManager.rejectUnknown(path, "$", root, ROOT_FIELDS);
            ConfigManager.requireFields(path, "$", root, ROOT_FIELDS);
            int schema = root.get("schema_version").getAsInt();
            String algorithm = root.get("mapping_algorithm_version").getAsString();
            if (schema != MappingStoreDocument.SCHEMA_VERSION
                    || !MappingStoreDocument.ALGORITHM_VERSION.equals(algorithm)) {
                backupIncompatible(schema, algorithm);
                throw new MappingStoreException(path, "Incompatible schema/algorithm; backup created and automatic migration refused");
            }
            if (!root.get("mappings").isJsonArray()) {
                throw new MappingStoreException(path, "$.mappings must be an array");
            }
            int index = 0;
            for (var element : root.getAsJsonArray("mappings")) {
                if (!element.isJsonObject()) {
                    throw new MappingStoreException(path, "$.mappings[" + index + "] must be an object");
                }
                var entry = element.getAsJsonObject();
                ConfigManager.rejectUnknown(path, "$.mappings[" + index + "]", entry, ENTRY_FIELDS);
                ConfigManager.requireFields(path, "$.mappings[" + index + "]", entry, ENTRY_FIELDS);
                index++;
            }
            try {
                return GSON.fromJson(root, MappingStoreDocument.class);
            } catch (RuntimeException exception) {
                throw new MappingStoreException(path, "Mapping validation failed: " + exception.getMessage(), exception);
            }
        } catch (MappingStoreException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new MappingStoreException(path, "Corrupt mapping file; data was not replaced: " + exception.getMessage(), exception);
        }
    }

    public void save(Collection<StoredMapping> mappings) {
        var document = new MappingStoreDocument(MappingStoreDocument.SCHEMA_VERSION,
                MappingStoreDocument.ALGORITHM_VERSION, new ArrayList<>(mappings));
        try {
            AtomicFiles.write(path, serialize(document));
        } catch (IOException exception) {
            throw new MappingStoreException(path, "Atomic mapping write failed", exception);
        }
    }

    /** Existing keys always win; new keys append in stable order. */
    public MappingStoreDocument mergePreservingAssignments(MappingStoreDocument existing,
                                                            Collection<StoredMapping> discovered) {
        Map<String, StoredMapping> merged = new LinkedHashMap<>();
        existing.mappings().stream().sorted().forEach(mapping -> merged.put(mapping.key(), mapping));
        discovered.stream().sorted().forEach(mapping -> merged.compute(mapping.key(), (key, previous) -> {
            if (previous == null) {
                return mapping;
            }
            if (previous.strategy().equals(mapping.strategy())
                    && previous.clientCarrier().equals(mapping.clientCarrier())) {
                return new StoredMapping(previous.registryId(), previous.contentType(), previous.state(),
                        previous.strategy(), previous.clientCarrier(), mapping.resourceHash(), previous.createdWith(),
                        mapping.lastValidatedWith());
            }
            return previous;
        }));
        return new MappingStoreDocument(MappingStoreDocument.SCHEMA_VERSION,
                MappingStoreDocument.ALGORITHM_VERSION, List.copyOf(merged.values()));
    }

    public byte[] serialize(MappingStoreDocument document) {
        return (GSON.toJson(document) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public Path path() {
        return path;
    }

    private void backupIncompatible(int schema, String algorithm) {
        var safeAlgorithm = algorithm.replaceAll("[^a-zA-Z0-9._-]", "_");
        var backup = path.resolveSibling(path.getFileName() + ".backup-schema-" + schema + "-" + safeAlgorithm);
        try {
            if (Files.notExists(backup)) {
                Files.copy(path, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
        } catch (IOException exception) {
            throw new MappingStoreException(path, "Could not back up incompatible mapping store", exception);
        }
    }
}
