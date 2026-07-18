/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.config.ConfigManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/** Validated mapping backups and restart-only rollback preparation. */
public final class MappingBackupService {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long MAX_MAPPING_BYTES = 32L * 1024L * 1024L;
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Set<String> BACKUP_FIELDS = Set.of("schema_version", "backup_id", "created_at",
            "sha256", "size_bytes", "minecraft_version", "project_version", "mapping_schema_version",
            "mapping_algorithm_version");
    private static final Set<String> PENDING_FIELDS = Set.of("schema_version", "backup_id", "sha256",
            "minecraft_version", "mapping_algorithm_version");

    private final Path root;
    private final Path backupsDirectory;
    private final Path pendingPath;
    private final Path pendingMetadataPath;
    private final PersistentMappingStore store;
    private final Clock clock;

    public MappingBackupService(Path polymcConfigRoot) {
        this(polymcConfigRoot, Clock.systemUTC());
    }

    MappingBackupService(Path polymcConfigRoot, Clock clock) {
        this.root = polymcConfigRoot.toAbsolutePath().normalize();
        this.backupsDirectory = contained(root.resolve("backups").resolve("mappings"));
        this.pendingPath = contained(root.resolve("mappings-v1.rollback-pending.json"));
        this.pendingMetadataPath = contained(root.resolve("mappings-v1.rollback-pending.meta.json"));
        this.store = new PersistentMappingStore(root);
        this.clock = clock;
    }

    public StoreStatus validateCurrent() {
        if (Files.notExists(store.path())) {
            return new StoreStatus(false, 0, "", MappingStoreDocument.SCHEMA_VERSION,
                    MappingStoreDocument.ALGORITHM_VERSION);
        }
        try {
            byte[] bytes = boundedRead(store.path());
            var document = store.parse(bytes);
            return new StoreStatus(true, document.mappings().size(), sha256(bytes),
                    document.schemaVersion(), document.mappingAlgorithmVersion());
        } catch (IOException exception) {
            throw new MappingStoreException(store.path(), "Could not validate mapping store", exception);
        }
    }

    public BackupInfo backupCurrent(String minecraftVersion, String projectVersion) {
        if (Files.notExists(store.path())) {
            throw new MappingStoreException(store.path(), "Cannot back up a mapping store that does not exist");
        }
        try {
            byte[] bytes = boundedRead(store.path());
            var document = store.parse(bytes);
            String hash = sha256(bytes);
            String id = uniqueBackupId(BACKUP_TIME.format(Instant.now(clock)) + "-" + hash.substring(0, 12));
            var dataPath = backupDataPath(id);
            var metadataPath = backupMetadataPath(id);
            var metadata = new BackupMetadata(1, id, Instant.now(clock).toString(), hash, bytes.length,
                    minecraftVersion, projectVersion, document.schemaVersion(),
                    document.mappingAlgorithmVersion());
            AtomicFiles.write(dataPath, bytes);
            AtomicFiles.write(metadataPath, json(metadata));
            return metadata.toInfo();
        } catch (IOException exception) {
            throw new MappingStoreException(store.path(), "Could not create mapping backup", exception);
        }
    }

    public List<BackupInfo> listBackups() {
        if (Files.notExists(backupsDirectory)) {
            return List.of();
        }
        try (var paths = Files.list(backupsDirectory)) {
            var metadataPaths = paths.filter(path -> path.getFileName().toString().endsWith(".meta.json"))
                    .sorted().toList();
            var result = new ArrayList<BackupInfo>();
            for (var metadataPath : metadataPaths) {
                result.add(readBackupMetadata(metadataPath).toInfo());
            }
            result.sort(Comparator.comparing(BackupInfo::id));
            return List.copyOf(result);
        } catch (IOException exception) {
            throw new MappingStoreException(backupsDirectory, "Could not list mapping backups", exception);
        }
    }

    public RollbackPreparation prepareRollback(String backupId, String minecraftVersion,
                                                String projectVersion) {
        var validated = validateBackup(backupId, minecraftVersion);
        BackupInfo safetyBackup = Files.exists(store.path())
                ? backupCurrent(minecraftVersion, projectVersion) : null;
        var pending = new PendingRollback(1, backupId, validated.info().sha256(), minecraftVersion,
                validated.document().mappingAlgorithmVersion());
        try {
            AtomicFiles.write(pendingPath, validated.bytes());
            AtomicFiles.write(pendingMetadataPath, json(pending));
            return new RollbackPreparation(backupId,
                    safetyBackup == null ? null : safetyBackup.id(), pendingPath.getFileName().toString());
        } catch (IOException exception) {
            throw new MappingStoreException(pendingPath, "Could not prepare mapping rollback", exception);
        }
    }

    /** Activates a previously validated rollback before planning; live plans are never replaced. */
    public boolean activatePendingRollback(String minecraftVersion, String projectVersion) {
        boolean hasData = Files.exists(pendingPath);
        boolean hasMetadata = Files.exists(pendingMetadataPath);
        if (!hasData && !hasMetadata) {
            return false;
        }
        if (hasData != hasMetadata) {
            throw new MappingStoreException(pendingPath,
                    "Incomplete pending rollback: data and metadata must both exist");
        }
        try {
            var pending = readPendingMetadata();
            if (!minecraftVersion.equals(pending.minecraftVersion())) {
                throw new MappingStoreException(pendingPath, "Pending rollback targets Minecraft "
                        + pending.minecraftVersion() + ", expected " + minecraftVersion);
            }
            if (!MappingStoreDocument.ALGORITHM_VERSION.equals(pending.mappingAlgorithmVersion())) {
                throw new MappingStoreException(pendingPath, "Pending rollback uses incompatible algorithm "
                        + pending.mappingAlgorithmVersion());
            }
            byte[] bytes = boundedRead(pendingPath);
            if (!MessageDigest.isEqual(pending.sha256().getBytes(StandardCharsets.US_ASCII),
                    sha256(bytes).getBytes(StandardCharsets.US_ASCII))) {
                throw new MappingStoreException(pendingPath, "Pending rollback checksum mismatch");
            }
            store.parse(bytes, pendingPath);
            if (Files.exists(store.path())) {
                byte[] current = boundedRead(store.path());
                if (!MessageDigest.isEqual(current, bytes)) {
                    backupCurrent(minecraftVersion, projectVersion);
                    AtomicFiles.write(store.path(), bytes);
                }
            } else {
                AtomicFiles.write(store.path(), bytes);
            }
            Files.delete(pendingPath);
            Files.delete(pendingMetadataPath);
            return true;
        } catch (IOException exception) {
            throw new MappingStoreException(pendingPath, "Could not activate pending rollback", exception);
        }
    }

    public DryRunResult dryRun(MappingStoreDocument proposed) {
        MappingStoreDocument current = Files.exists(store.path()) ? store.load() : MappingStoreDocument.empty();
        byte[] proposedBytes = store.serialize(proposed);
        return new DryRunResult(MappingPlanDiff.compare(current, proposed), proposedBytes.length,
                sha256(proposedBytes));
    }

    public Path pendingPath() {
        return pendingPath;
    }

    private ValidatedBackup validateBackup(String backupId, String minecraftVersion) {
        requireBackupId(backupId);
        var metadataPath = backupMetadataPath(backupId);
        var dataPath = backupDataPath(backupId);
        if (!Files.isRegularFile(metadataPath) || !Files.isRegularFile(dataPath)) {
            throw new MappingStoreException(dataPath, "Unknown or incomplete mapping backup " + backupId);
        }
        try {
            var metadata = readBackupMetadata(metadataPath);
            if (!metadata.backupId().equals(backupId)) {
                throw new MappingStoreException(metadataPath, "Backup metadata ID mismatch");
            }
            if (!metadata.minecraftVersion().equals(minecraftVersion)) {
                throw new MappingStoreException(metadataPath, "Backup targets Minecraft "
                        + metadata.minecraftVersion() + ", expected " + minecraftVersion);
            }
            byte[] bytes = boundedRead(dataPath);
            String actualHash = sha256(bytes);
            if (bytes.length != metadata.sizeBytes() || !MessageDigest.isEqual(
                    actualHash.getBytes(StandardCharsets.US_ASCII),
                    metadata.sha256().getBytes(StandardCharsets.US_ASCII))) {
                throw new MappingStoreException(dataPath, "Backup checksum or size mismatch");
            }
            var document = store.parse(bytes, dataPath);
            if (document.schemaVersion() != metadata.mappingSchemaVersion()
                    || !document.mappingAlgorithmVersion().equals(metadata.mappingAlgorithmVersion())) {
                throw new MappingStoreException(dataPath, "Backup document does not match its metadata");
            }
            return new ValidatedBackup(metadata.toInfo(), document, bytes);
        } catch (IOException exception) {
            throw new MappingStoreException(dataPath, "Could not validate mapping backup", exception);
        }
    }

    private BackupMetadata readBackupMetadata(Path metadataPath) throws IOException {
        var rootObject = JsonParser.parseString(Files.readString(metadataPath, StandardCharsets.UTF_8))
                .getAsJsonObject();
        ConfigManager.rejectUnknown(metadataPath, "$", rootObject, BACKUP_FIELDS);
        ConfigManager.requireFields(metadataPath, "$", rootObject, BACKUP_FIELDS);
        try {
            var metadata = GSON.fromJson(rootObject, BackupMetadata.class);
            metadata.validate();
            return metadata;
        } catch (RuntimeException exception) {
            throw new MappingStoreException(metadataPath,
                    "Invalid backup metadata: " + exception.getMessage(), exception);
        }
    }

    private PendingRollback readPendingMetadata() throws IOException {
        var rootObject = JsonParser.parseString(Files.readString(pendingMetadataPath, StandardCharsets.UTF_8))
                .getAsJsonObject();
        ConfigManager.rejectUnknown(pendingMetadataPath, "$", rootObject, PENDING_FIELDS);
        ConfigManager.requireFields(pendingMetadataPath, "$", rootObject, PENDING_FIELDS);
        try {
            var pending = GSON.fromJson(rootObject, PendingRollback.class);
            pending.validate();
            return pending;
        } catch (RuntimeException exception) {
            throw new MappingStoreException(pendingMetadataPath,
                    "Invalid pending rollback metadata: " + exception.getMessage(), exception);
        }
    }

    private byte[] boundedRead(Path path) throws IOException {
        long size = Files.size(path);
        if (size > MAX_MAPPING_BYTES) {
            throw new MappingStoreException(path, "Mapping file exceeds hard limit " + MAX_MAPPING_BYTES);
        }
        return Files.readAllBytes(path);
    }

    private String uniqueBackupId(String base) {
        String candidate = base;
        for (int suffix = 1; Files.exists(backupDataPath(candidate))
                || Files.exists(backupMetadataPath(candidate)); suffix++) {
            candidate = base + "-" + suffix;
        }
        return candidate;
    }

    private Path backupDataPath(String backupId) {
        requireBackupId(backupId);
        return contained(backupsDirectory.resolve(backupId + ".json"));
    }

    private Path backupMetadataPath(String backupId) {
        requireBackupId(backupId);
        return contained(backupsDirectory.resolve(backupId + ".meta.json"));
    }

    private static void requireBackupId(String backupId) {
        if (backupId == null || !backupId.matches("[0-9]{8}T[0-9]{9}Z-[0-9a-f]{12}(?:-[1-9][0-9]*)?")) {
            throw new IllegalArgumentException("Invalid mapping backup ID");
        }
    }

    private Path contained(Path candidate) {
        var normalized = candidate.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Mapping operation escaped its configured root");
        }
        return normalized;
    }

    private static byte[] json(Object value) {
        return (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record StoreStatus(boolean present, int entryCount, String sha256, int schemaVersion,
                              String algorithmVersion) {
    }

    public record BackupInfo(String id, String createdAt, String sha256, long sizeBytes,
                             String minecraftVersion, String projectVersion, int mappingSchemaVersion,
                             String mappingAlgorithmVersion) {
    }

    public record RollbackPreparation(String sourceBackupId, String safetyBackupId,
                                      String pendingFileName) {
    }

    public record DryRunResult(MappingPlanDiff diff, long proposedSizeBytes, String proposedSha256) {
    }

    private record ValidatedBackup(BackupInfo info, MappingStoreDocument document, byte[] bytes) {
        private ValidatedBackup {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private record BackupMetadata(
            @SerializedName("schema_version") int schemaVersion,
            @SerializedName("backup_id") String backupId,
            @SerializedName("created_at") String createdAt,
            String sha256,
            @SerializedName("size_bytes") long sizeBytes,
            @SerializedName("minecraft_version") String minecraftVersion,
            @SerializedName("project_version") String projectVersion,
            @SerializedName("mapping_schema_version") int mappingSchemaVersion,
            @SerializedName("mapping_algorithm_version") String mappingAlgorithmVersion) {
        void validate() {
            if (schemaVersion != 1 || sizeBytes < 0 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Unsupported backup metadata schema or checksum");
            }
            requireBackupId(backupId);
            Instant.parse(createdAt);
            if (minecraftVersion == null || minecraftVersion.isBlank()
                    || projectVersion == null || projectVersion.isBlank()
                    || mappingSchemaVersion != MappingStoreDocument.SCHEMA_VERSION
                    || !MappingStoreDocument.ALGORITHM_VERSION.equals(mappingAlgorithmVersion)) {
                throw new IllegalArgumentException("Incompatible backup metadata");
            }
        }

        BackupInfo toInfo() {
            validate();
            return new BackupInfo(backupId, createdAt, sha256, sizeBytes, minecraftVersion, projectVersion,
                    mappingSchemaVersion, mappingAlgorithmVersion);
        }
    }

    private record PendingRollback(
            @SerializedName("schema_version") int schemaVersion,
            @SerializedName("backup_id") String backupId,
            String sha256,
            @SerializedName("minecraft_version") String minecraftVersion,
            @SerializedName("mapping_algorithm_version") String mappingAlgorithmVersion) {
        void validate() {
            if (schemaVersion != 1 || sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Unsupported pending rollback metadata");
            }
            requireBackupId(backupId);
            if (minecraftVersion == null || minecraftVersion.isBlank()
                    || !MappingStoreDocument.ALGORITHM_VERSION.equals(mappingAlgorithmVersion)) {
                throw new IllegalArgumentException("Incompatible pending rollback metadata");
            }
        }
    }
}
