/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import io.github.polymcreborn.api.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentMappingStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsEntriesInStableKeyOrder() {
        var store = new PersistentMappingStore(temporaryDirectory);
        var zeta = mapping("zeta:block", ContentType.BLOCK, "polymer:block/17");
        var alpha = mapping("alpha:item", ContentType.ITEM, "minecraft:paper");

        store.save(List.of(zeta, alpha));
        var loaded = store.load();

        assertEquals(List.of(zeta, alpha), loaded.mappings());
        assertEquals(MappingStoreDocument.SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(MappingStoreDocument.ALGORITHM_VERSION, loaded.mappingAlgorithmVersion());
    }

    @Test
    void identicalInputsProduceByteIdenticalFilesAcrossRuns() throws IOException {
        var firstDirectory = Files.createDirectory(temporaryDirectory.resolve("first"));
        var secondDirectory = Files.createDirectory(temporaryDirectory.resolve("second"));
        var entries = List.of(
                mapping("demo:z", ContentType.BLOCK, "polymer:block/3"),
                mapping("demo:a", ContentType.ITEM, "minecraft:paper"));
        var first = new PersistentMappingStore(firstDirectory);
        var second = new PersistentMappingStore(secondDirectory);

        first.save(entries);
        second.save(entries.reversed());

        assertArrayEquals(Files.readAllBytes(first.path()), Files.readAllBytes(second.path()));
        assertArrayEquals(first.serialize(first.load()), second.serialize(second.load()));
    }

    @Test
    void corruptMappingDataIsFatalAndNeverSilentlyReplaced() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        byte[] corrupt = "{ definitely-not-json".getBytes(StandardCharsets.UTF_8);
        Files.write(store.path(), corrupt);

        var failure = assertThrows(MappingStoreException.class, store::load);

        assertTrue(failure.getMessage().contains("Corrupt mapping file"));
        assertArrayEquals(corrupt, Files.readAllBytes(store.path()));
    }

    @Test
    void incompatibleSchemaIsBackedUpBeforeMigrationIsRefused() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        var incompatible = """
                {
                  "schema_version": 99,
                  "mapping_algorithm_version": "future",
                  "mappings": []
                }
                """;
        Files.writeString(store.path(), incompatible, StandardCharsets.UTF_8);

        assertThrows(MappingStoreException.class, store::load);

        var backup = store.path().resolveSibling("mappings-v1.json.backup-schema-99-future");
        assertTrue(Files.isRegularFile(backup));
        assertEquals(incompatible, Files.readString(backup, StandardCharsets.UTF_8));
        assertEquals(incompatible, Files.readString(store.path(), StandardCharsets.UTF_8));
    }

    @Test
    void atomicSaveLeavesNoTemporaryFiles() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:first", ContentType.ITEM, "minecraft:paper")));
        store.save(List.of(mapping("demo:second", ContentType.ITEM, "minecraft:stone")));

        assertEquals("demo:second", store.load().mappings().getFirst().registryId());
        try (var files = Files.list(temporaryDirectory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void interruptedTemporaryFileNeverReplacesTheLastGoodStore() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:good", ContentType.ITEM, "minecraft:paper")));
        Files.writeString(temporaryDirectory.resolve("mappings-v1.json.interrupted.tmp"),
                "partial", StandardCharsets.UTF_8);

        assertEquals("demo:good", store.load().mappings().getFirst().registryId());
        assertTrue(Files.exists(temporaryDirectory.resolve("mappings-v1.json.interrupted.tmp")));
    }

    @Test
    void mergePreservesExistingAssignmentsAndOnlyUpdatesValidationVersion() {
        var store = new PersistentMappingStore(temporaryDirectory);
        var existing = mapping("demo:fixed", ContentType.BLOCK, "polymer:block/1");
        var attemptedRemap = new StoredMapping("demo:fixed", ContentType.BLOCK, "", "textured-full-cube",
                "polymer:block/99", "", "0.2.0", "0.2.0");
        var unchanged = new StoredMapping("demo:fixed", ContentType.BLOCK, "", "textured-full-cube",
                "polymer:block/1", "", "0.1.0", "0.2.0");
        var oldDocument = new MappingStoreDocument(1, MappingStoreDocument.ALGORITHM_VERSION, List.of(existing));

        var preserved = store.mergePreservingAssignments(oldDocument, List.of(attemptedRemap));
        var validated = store.mergePreservingAssignments(oldDocument, List.of(unchanged));

        assertEquals("polymer:block/1", preserved.mappings().getFirst().clientCarrier());
        assertEquals("0.1.0", preserved.mappings().getFirst().lastValidatedWith());
        assertEquals("0.2.0", validated.mappings().getFirst().lastValidatedWith());
        assertEquals("0.1.0", validated.mappings().getFirst().createdWith());
    }

    @Test
    void validatesIdentifiersHashesAndDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> new StoredMapping(
                "missing_namespace", ContentType.ITEM, "", "item", "minecraft:paper", "",
                "0.1", "0.1"));
        assertThrows(IllegalArgumentException.class, () -> new StoredMapping(
                "Demo:Uppercase", ContentType.ITEM, "", "item", "minecraft:paper", "",
                "0.1", "0.1"));
        assertThrows(IllegalArgumentException.class, () -> new StoredMapping(
                "demo:item", ContentType.ITEM, "active=true", "item", "minecraft:paper", "",
                "0.1", "0.1"));
        assertThrows(IllegalArgumentException.class, () -> new StoredMapping(
                "demo:block", ContentType.BLOCK, "active", "item", "minecraft:paper", "",
                "0.1", "0.1"));
        assertThrows(IllegalArgumentException.class, () -> new StoredMapping(
                "demo:item", ContentType.ITEM, "", "item", "not a carrier", "",
                "0.1", "0.1"));
        assertThrows(IllegalArgumentException.class, () -> new StoredMapping(
                "demo:item", ContentType.ITEM, "", "bad strategy", "minecraft:paper", "",
                "0.1", "0.1"));
        assertThrows(IllegalArgumentException.class, () -> new StoredMapping(
                "demo:item", ContentType.ITEM, "", "item", "minecraft:paper", "not-a-hash",
                "0.1", "0.1"));
        var duplicate = mapping("demo:item", ContentType.ITEM, "minecraft:paper");
        assertThrows(IllegalArgumentException.class,
                () -> new MappingStoreDocument(1, MappingStoreDocument.ALGORITHM_VERSION,
                        List.of(duplicate, duplicate)));
    }

    private static StoredMapping mapping(String id, ContentType type, String carrier) {
        return new StoredMapping(id, type, "", type == ContentType.BLOCK ? "textured-full-cube" : "semantic-item",
                carrier, "", "0.1.0", "0.1.0");
    }
}
