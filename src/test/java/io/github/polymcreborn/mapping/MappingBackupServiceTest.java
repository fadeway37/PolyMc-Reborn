/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import io.github.polymcreborn.api.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MappingBackupServiceTest {
    private static final String MINECRAFT = "26.1.2";
    private static final String VERSION = "0.2.0-alpha.1+26.1.2";

    @TempDir
    Path temporaryDirectory;

    @Test
    void backupRollbackIsStagedAndActivatedOnlyOnRestart() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:old", "minecraft:paper")));
        byte[] oldBytes = Files.readAllBytes(store.path());
        var service = service();
        var oldBackup = service.backupCurrent(MINECRAFT, VERSION);

        store.save(List.of(mapping("demo:new", "minecraft:stone")));
        byte[] newBytes = Files.readAllBytes(store.path());
        var preparation = service.prepareRollback(oldBackup.id(), MINECRAFT, VERSION);

        assertArrayEquals(newBytes, Files.readAllBytes(store.path()),
                "preparing rollback must not change the live store");
        assertTrue(Files.isRegularFile(service.pendingPath()));
        assertNotNull(preparation.safetyBackupId());
        assertEquals("demo:new", store.load().mappings().getFirst().registryId());

        assertTrue(service.activatePendingRollback(MINECRAFT, VERSION));
        assertArrayEquals(oldBytes, Files.readAllBytes(store.path()));
        assertEquals("demo:old", store.load().mappings().getFirst().registryId());
        assertFalse(Files.exists(service.pendingPath()));
        assertTrue(service.listBackups().size() >= 2);
    }

    @Test
    void dryRunProducesDiffWithoutWritingAnyFile() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:old", "minecraft:paper")));
        byte[] before = Files.readAllBytes(store.path());
        var service = service();
        var proposed = new MappingStoreDocument(MappingStoreDocument.SCHEMA_VERSION,
                MappingStoreDocument.ALGORITHM_VERSION,
                List.of(mapping("demo:old", "minecraft:paper"),
                        mapping("demo:new", "minecraft:stone")));

        var result = service.dryRun(proposed);

        assertEquals(1, result.diff().counts().get(MappingPlanDiff.ChangeKind.ADDED));
        assertArrayEquals(before, Files.readAllBytes(store.path()));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of("mappings-v1.json"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    @Test
    void corruptAndWrongVersionBackupsAreRejectedWithoutTouchingPrimary() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:old", "minecraft:paper")));
        var service = service();
        var backup = service.backupCurrent(MINECRAFT, VERSION);
        store.save(List.of(mapping("demo:current", "minecraft:stone")));
        byte[] current = Files.readAllBytes(store.path());

        assertThrows(MappingStoreException.class,
                () -> service.prepareRollback(backup.id(), "26.2", VERSION));
        assertArrayEquals(current, Files.readAllBytes(store.path()));

        var dataPath = temporaryDirectory.resolve("backups/mappings/" + backup.id() + ".json");
        Files.writeString(dataPath, "corrupt", StandardCharsets.UTF_8);
        assertThrows(MappingStoreException.class,
                () -> service.prepareRollback(backup.id(), MINECRAFT, VERSION));
        assertArrayEquals(current, Files.readAllBytes(store.path()));
    }

    @Test
    void incompletePendingRollbackFailsClosed() throws IOException {
        var service = service();
        Files.writeString(service.pendingPath(), "{}", StandardCharsets.UTF_8);

        var failure = assertThrows(MappingStoreException.class,
                () -> service.activatePendingRollback(MINECRAFT, VERSION));

        assertTrue(failure.getMessage().contains("Incomplete pending rollback"));
        assertTrue(Files.exists(service.pendingPath()));
    }

    @Test
    void interruptedPendingMetadataWriteFailsClosedWithoutTouchingPrimary() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:current", "minecraft:paper")));
        byte[] current = Files.readAllBytes(store.path());
        var service = service();
        var pendingMetadata = service.pendingPath().resolveSibling(
                "mappings-v1.rollback-pending.meta.json");
        Files.writeString(pendingMetadata, "{}", StandardCharsets.UTF_8);

        var failure = assertThrows(MappingStoreException.class,
                () -> service.activatePendingRollback(MINECRAFT, VERSION));

        assertTrue(failure.getMessage().contains("Incomplete pending rollback"));
        assertArrayEquals(current, Files.readAllBytes(store.path()));
        assertTrue(Files.exists(pendingMetadata));
    }

    @Test
    void tamperedPendingRollbackFailsClosedAndRemainsAvailableForDiagnosis() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:rollback", "minecraft:paper")));
        var service = service();
        var backup = service.backupCurrent(MINECRAFT, VERSION);
        store.save(List.of(mapping("demo:current", "minecraft:stone")));
        byte[] current = Files.readAllBytes(store.path());
        service.prepareRollback(backup.id(), MINECRAFT, VERSION);
        Files.writeString(service.pendingPath(), "{}", StandardCharsets.UTF_8);

        var failure = assertThrows(MappingStoreException.class,
                () -> service.activatePendingRollback(MINECRAFT, VERSION));

        assertTrue(failure.getMessage().contains("checksum mismatch"));
        assertArrayEquals(current, Files.readAllBytes(store.path()));
        assertTrue(Files.exists(service.pendingPath()));
        assertTrue(Files.exists(service.pendingPath().resolveSibling(
                "mappings-v1.rollback-pending.meta.json")));
    }

    @Test
    void malformedBackupMetadataRootIsReportedAsMappingError() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:backup", "minecraft:paper")));
        var service = service();
        var backup = service.backupCurrent(MINECRAFT, VERSION);
        Path metadata = temporaryDirectory.resolve("backups/mappings/" + backup.id() + ".meta.json");
        Files.writeString(metadata, "[]", StandardCharsets.UTF_8);

        var failure = assertThrows(MappingStoreException.class,
                () -> service.prepareRollback(backup.id(), MINECRAFT, VERSION));

        assertTrue(failure.getMessage().contains("Invalid backup metadata"));
    }

    @Test
    void pendingRollbackForAnotherMinecraftVersionCannotReplacePrimary() throws IOException {
        var store = new PersistentMappingStore(temporaryDirectory);
        store.save(List.of(mapping("demo:rollback", "minecraft:paper")));
        var service = service();
        var backup = service.backupCurrent(MINECRAFT, VERSION);
        store.save(List.of(mapping("demo:current", "minecraft:stone")));
        byte[] current = Files.readAllBytes(store.path());
        service.prepareRollback(backup.id(), MINECRAFT, VERSION);

        var failure = assertThrows(MappingStoreException.class,
                () -> service.activatePendingRollback("26.2", VERSION));

        assertTrue(failure.getMessage().contains("targets Minecraft 26.1.2"));
        assertArrayEquals(current, Files.readAllBytes(store.path()));
        assertTrue(Files.exists(service.pendingPath()));
    }

    @Test
    void rejectsPathTraversalBackupIdentifiers() {
        var service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRollback("../../server.properties", MINECRAFT, VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRollback("..\\..\\server.properties", MINECRAFT, VERSION));
        assertThrows(IllegalArgumentException.class,
                () -> service.prepareRollback("C:/server.properties", MINECRAFT, VERSION));
    }

    @Test
    void rejectsBackupDirectorySymlinkThatEscapesConfiguredRoot() throws IOException {
        Path root = temporaryDirectory.resolve("root");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(root.resolve("backups"));
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(root.resolve("backups/mappings"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "symbolic links are unavailable: " + unavailable.getClass().getSimpleName());
        }

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new MappingBackupService(root));
        assertTrue(failure.getMessage().contains("symbolic link"));
    }

    private MappingBackupService service() {
        return new MappingBackupService(temporaryDirectory,
                Clock.fixed(Instant.parse("2026-07-18T00:00:00Z"), ZoneOffset.UTC));
    }

    private static StoredMapping mapping(String id, String carrier) {
        return new StoredMapping(id, ContentType.ITEM, "", "semantic-item", carrier, "",
                VERSION, VERSION);
    }
}
