/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.diagnostics.BoundedDiagnosticCollector;
import io.github.polymcreborn.mapping.MappingCapacityException;
import io.github.polymcreborn.mapping.MappingPlan;
import io.github.polymcreborn.mapping.MappingStoreDocument;
import io.github.polymcreborn.mapping.PersistentMappingStore;
import io.github.polymcreborn.mapping.StoredMapping;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Applies the frozen plan using public Polymer overlays and the shared Polymer Blocks pool. */
public final class PolymerCompatibilityBackend {
    private static final long MAX_HASHED_MODEL_BYTES = 8_388_608L;
    private final PersistentMappingStore store;
    private final BoundedDiagnosticCollector diagnostics;
    private final boolean persistentMappings;
    private final boolean customModelsEnabled;
    private final Map<String, SafeItemOverlay> pendingSemanticItems = new TreeMap<>();

    public PolymerCompatibilityBackend(PersistentMappingStore store, BoundedDiagnosticCollector diagnostics) {
        this(store, diagnostics, true, true);
    }

    public PolymerCompatibilityBackend(PersistentMappingStore store, BoundedDiagnosticCollector diagnostics,
                                       boolean persistentMappings) {
        this(store, diagnostics, persistentMappings, true);
    }

    public PolymerCompatibilityBackend(PersistentMappingStore store, BoundedDiagnosticCollector diagnostics,
                                       boolean persistentMappings, boolean customModelsEnabled) {
        this.store = store;
        this.diagnostics = diagnostics;
        this.persistentMappings = persistentMappings;
        this.customModelsEnabled = customModelsEnabled;
    }

    public MappingPlan apply(MappingPlan proposedPlan, String projectVersion) {
        pendingSemanticItems.clear();
        MappingStoreDocument existing = persistentMappings ? store.load() : MappingStoreDocument.empty();
        var updated = new LinkedHashMap<String, MappingDecision>();
        proposedPlan.orderedDecisions().forEach(decision -> updated.put(decision.descriptor().key(), decision));
        var newMappings = new ArrayList<StoredMapping>();

        applyItems(proposedPlan, existing, updated, newMappings, projectVersion);
        applyBlocks(proposedPlan, existing, updated, newMappings, projectVersion);

        if (persistentMappings) {
            var merged = store.mergePreservingAssignments(existing, newMappings);
            store.save(merged.mappings());
        }
        return proposedPlan.replaceDecisions(List.copyOf(updated.values()));
    }

    private void applyItems(MappingPlan plan, MappingStoreDocument existing,
                            Map<String, MappingDecision> updated,
                            List<StoredMapping> mappings, String projectVersion) {
        var existingItems = new HashMap<String, StoredMapping>();
        existing.mappings().stream()
                .filter(mapping -> mapping.contentType() == ContentType.ITEM && mapping.state().isEmpty())
                .sorted().forEach(mapping -> existingItems.put(mapping.registryId(), mapping));
        for (var decision : plan.orderedDecisions()) {
            if (decision.descriptor().contentType() != ContentType.ITEM || !isApplicable(decision)
                    || !"polymer".equals(decision.backend())) {
                continue;
            }
            var serverId = Identifier.parse(decision.descriptor().registryId());
            var serverItem = BuiltInRegistries.ITEM.getValue(serverId);
            if (PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, serverItem) instanceof PolymerItem
                    && !isAdministratorOverride(decision)) {
                if (decision.status() != MappingStatus.NATIVE) {
                    updated.put(decision.descriptor().key(), nativeRaceDecision(decision));
                }
                continue;
            }
            try {
                var persisted = existingItems.get(serverId.toString());
                boolean pendingSemantic = "semantic-item-material-unbound".equals(decision.strategy())
                        && persisted == null;
                var effective = persisted == null ? decision : withAssignment(decision, persisted.strategy(),
                        persisted.clientCarrier(), "A valid persisted item assignment was replayed");
                var carrierId = Identifier.parse(effective.clientCarrier());
                if (!carrierId.getNamespace().equals("minecraft") || !BuiltInRegistries.ITEM.containsKey(carrierId)) {
                    throw new IllegalArgumentException("Unknown vanilla carrier " + carrierId);
                }
                var carrier = BuiltInRegistries.ITEM.getValue(carrierId);
                var overlay = new SafeItemOverlay(serverItem == carrier
                        ? BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("paper")) : carrier,
                        customModelsEnabled && itemDefinitionExists(effective) ? serverId : null);
                PolymerItemUtils.registerOverlay(serverItem, overlay);
                if (pendingSemantic) {
                    pendingSemanticItems.put(serverId.toString(), overlay);
                } else {
                    if (persisted != null) {
                        ItemProjectionCacheStats.hit();
                    }
                    mappings.add(new StoredMapping(serverId.toString(), ContentType.ITEM, "", effective.strategy(),
                            carrierId.toString(), modelHash(effective, "item"), projectVersion, projectVersion));
                }
                updated.put(decision.descriptor().key(), customModelsEnabled ? effective
                        : withWarning(effective,
                        "Resource-pack generation is disabled; the vanilla carrier model is retained"));
            } catch (RuntimeException exception) {
                updated.put(decision.descriptor().key(), errorDecision(decision,
                        "Item overlay registration failed: " + exception.getMessage()));
            }
        }
    }

    /** Locks first-run semantic carriers after vanilla binds item components; no registry mutation occurs here. */
    public synchronized MappingPlan finalizeBoundItemCarriers(MappingPlan current, String projectVersion) {
        if (pendingSemanticItems.isEmpty()) {
            return current;
        }
        var updated = new LinkedHashMap<String, MappingDecision>();
        current.orderedDecisions().forEach(decision -> updated.put(decision.descriptor().key(), decision));
        var resolvedMappings = new ArrayList<StoredMapping>();
        var completed = new ArrayList<String>();
        for (var entry : pendingSemanticItems.entrySet()) {
            String registryId = entry.getKey();
            var decision = current.decision(ContentType.ITEM, registryId);
            if (decision == null) {
                continue;
            }
            try {
                Item serverItem = BuiltInRegistries.ITEM.getValue(Identifier.parse(registryId));
                if (!serverItem.builtInRegistryHolder().areComponentsBound()) {
                    continue;
                }
                String category = MinecraftContentScanner.classifyItem(serverItem);
                Item carrier = carrierForCategory(category);
                String carrierId = BuiltInRegistries.ITEM.getKey(carrier).toString();
                entry.getValue().setCarrier(carrier);
                ItemProjectionCacheStats.miss();
                var reasons = new ArrayList<>(decision.reasonChain());
                reasons.add("Item components were bound after overlay registration; the semantic carrier was locked before server startup");
                var warnings = decision.warnings().stream()
                        .filter(warning -> !warning.startsWith("Item default components were unavailable"))
                        .toList();
                var resolved = new MappingDecision(decision.descriptor(), decision.status(), decision.provider(),
                        decision.backend(), "semantic-item-" + category, carrierId,
                        "material".equals(category) ? 0.55 : 0.82,
                        "material".equals(category) ? 35 : 15, reasons, decision.resourceDependencies(),
                        warnings, decision.failureReason());
                updated.put(decision.descriptor().key(), resolved);
                resolvedMappings.add(new StoredMapping(registryId, ContentType.ITEM, "", resolved.strategy(),
                        carrierId, modelHash(resolved, "item"), projectVersion, projectVersion));
                completed.add(registryId);
            } catch (RuntimeException exception) {
                updated.put(decision.descriptor().key(), errorDecision(decision,
                        "Bound item semantic resolution failed: " + exception.getMessage()));
                completed.add(registryId);
            }
        }
        completed.forEach(pendingSemanticItems::remove);
        if (completed.isEmpty()) {
            return current;
        }
        if (persistentMappings && !resolvedMappings.isEmpty()) {
            var existing = store.load();
            store.save(store.mergePreservingAssignments(existing, resolvedMappings).mappings());
        }
        return current.replaceDecisions(List.copyOf(updated.values()));
    }

    public synchronized boolean hasPendingSemanticItems() {
        return !pendingSemanticItems.isEmpty();
    }

    private static Item carrierForCategory(String category) {
        return switch (category) {
            case "food" -> Items.APPLE;
            case "drink" -> Items.HONEY_BOTTLE;
            case "tool" -> Items.IRON_PICKAXE;
            case "armor" -> Items.IRON_CHESTPLATE;
            case "bow" -> Items.BOW;
            case "crossbow" -> Items.CROSSBOW;
            case "shield" -> Items.SHIELD;
            case "throwable" -> Items.SNOWBALL;
            case "block_item" -> Items.STONE;
            case "material" -> Items.PAPER;
            default -> throw new IllegalArgumentException("Unknown semantic item category " + category);
        };
    }

    private void applyBlocks(MappingPlan plan, MappingStoreDocument existing,
                             Map<String, MappingDecision> updated, List<StoredMapping> mappings,
                             String projectVersion) {
        var allExistingBlocks = new HashMap<String, StoredMapping>();
        existing.mappings().stream()
                .filter(mapping -> mapping.contentType() == ContentType.BLOCK && mapping.state().isEmpty())
                .sorted().forEach(mapping -> allExistingBlocks.put(mapping.registryId(), mapping));
        var pooledExistingBlocks = allExistingBlocks.values().stream()
                .filter(mapping -> mapping.strategy().equals("textured-full-cube"))
                .sorted().toList();
        var replayed = new HashMap<String, BlockState>();
        var replayFailures = new HashMap<String, String>();

        for (var mapping : pooledExistingBlocks) {
            try {
                BlockState state = allocate(mapping.registryId());
                String actual = stateKey(state);
                if (!actual.equals(mapping.clientCarrier())) {
                    replayFailures.put(mapping.registryId(), "Persisted carrier " + mapping.clientCarrier()
                            + " replayed as " + actual + "; refusing silent remap");
                } else {
                    replayed.put(mapping.registryId(), state);
                }
            } catch (RuntimeException exception) {
                replayFailures.put(mapping.registryId(), exception.getMessage());
            }
        }

        for (var decision : plan.orderedDecisions()) {
            if (decision.descriptor().contentType() != ContentType.BLOCK || !isApplicable(decision)
                    || !"polymer".equals(decision.backend())) {
                continue;
            }
            String registryId = decision.descriptor().registryId();
            var serverId = Identifier.parse(registryId);
            var serverBlock = BuiltInRegistries.BLOCK.getValue(serverId);
            if (PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, serverBlock) instanceof PolymerBlock
                    && !isAdministratorOverride(decision)) {
                if (decision.status() != MappingStatus.NATIVE) {
                    updated.put(decision.descriptor().key(), nativeRaceDecision(decision));
                }
                continue;
            }
            var persisted = allExistingBlocks.get(registryId);
            var effective = persisted == null ? decision : withAssignment(decision, persisted.strategy(),
                    persisted.clientCarrier(), "A valid persisted block assignment was replayed");
            if ("textured-full-cube".equals(effective.strategy()) && replayFailures.containsKey(registryId)) {
                updated.put(decision.descriptor().key(), errorDecision(decision, replayFailures.get(registryId)));
                continue;
            }
            try {
                BlockState carrier;
                if ("textured-full-cube".equals(effective.strategy())) {
                    carrier = replayed.containsKey(registryId) ? replayed.get(registryId) : allocate(registryId);
                } else if ("vanilla-fallback-state".equals(effective.strategy())) {
                    carrier = parseVanillaState(effective.clientCarrier());
                    if (persisted != null && !stateKey(carrier).equals(persisted.clientCarrier())) {
                        throw new IllegalArgumentException("Persisted vanilla state is not canonical: "
                                + persisted.clientCarrier());
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported Polymer block strategy " + effective.strategy());
                }
                PolymerBlockUtils.registerOverlay(serverBlock, new PlannedBlockOverlay(carrier));
                String carrierKey = stateKey(carrier);
                mappings.add(new StoredMapping(registryId, ContentType.BLOCK, "", effective.strategy(), carrierKey,
                        modelHash(effective, "block"), projectVersion, projectVersion));
                var applied = withAssignment(effective, effective.strategy(), carrierKey,
                        "Polymer block carrier was validated and frozen");
                updated.put(decision.descriptor().key(), customModelsEnabled ? applied
                        : withWarning(applied,
                        "Resource-pack generation is disabled; custom block textures are unavailable"));
            } catch (RuntimeException exception) {
                updated.put(decision.descriptor().key(), errorDecision(decision,
                        "Block overlay registration failed: " + exception.getMessage()));
            }
        }
    }

    private BlockState allocate(String registryId) {
        var id = Identifier.parse(registryId);
        int remaining = PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.FULL_BLOCK);
        if (remaining <= 0) {
            throw new MappingCapacityException(registryId, "Polymer FULL_BLOCK", remaining);
        }
        var model = Identifier.fromNamespaceAndPath(id.getNamespace(), "block/" + id.getPath());
        var state = PolymerBlockResourceUtils.requestBlock(BlockModelType.FULL_BLOCK, PolymerBlockModel.of(model));
        if (state == null) {
            throw new MappingCapacityException(registryId, "Polymer FULL_BLOCK", 0);
        }
        return state;
    }

    private static boolean isApplicable(MappingDecision decision) {
        return decision.status() == MappingStatus.HEURISTIC || decision.status() == MappingStatus.FALLBACK
                || decision.status() == MappingStatus.PROFILE || decision.status() == MappingStatus.EXPLICIT;
    }

    private MappingDecision errorDecision(MappingDecision original, String failure) {
        diagnostics.record("backend.apply.failed", original.descriptor().registryId(), failure,
                io.github.polymcreborn.api.DiagnosticCollector.Severity.ERROR);
        var reasons = new ArrayList<>(original.reasonChain());
        reasons.add(failure);
        return new MappingDecision(original.descriptor(), MappingStatus.ERROR, original.provider(), "polymer",
                original.strategy(), original.clientCarrier(), 0, 100, reasons,
                original.resourceDependencies(), original.warnings(), failure);
    }

    private static MappingDecision nativeRaceDecision(MappingDecision original) {
        var reasons = new ArrayList<>(original.reasonChain());
        reasons.add("A native Polymer overlay appeared before backend application; it was preserved");
        return new MappingDecision(original.descriptor(), MappingStatus.NATIVE, "native-polymer", "polymer",
                "preserve-native", original.descriptor().registryId(), 1, 0, reasons,
                original.resourceDependencies(), original.warnings(), null);
    }

    private static MappingDecision withAssignment(MappingDecision original, String strategy,
                                                  String carrier, String reason) {
        var reasons = new ArrayList<>(original.reasonChain());
        reasons.add(reason);
        return new MappingDecision(original.descriptor(), original.status(), original.provider(), original.backend(),
                strategy, carrier, original.confidence(), original.degradation(), reasons,
                original.resourceDependencies(), original.warnings(), original.failureReason());
    }

    private static MappingDecision withWarning(MappingDecision original, String warning) {
        var warnings = new ArrayList<>(original.warnings());
        warnings.add(warning);
        return new MappingDecision(original.descriptor(), original.status(), original.provider(), original.backend(),
                original.strategy(), original.clientCarrier(), original.confidence(), original.degradation(),
                original.reasonChain(), original.resourceDependencies(), warnings, original.failureReason());
    }

    private static BlockState parseVanillaState(String serialized) {
        int bracket = serialized.indexOf('[');
        String idText = bracket < 0 ? serialized : serialized.substring(0, bracket);
        if (bracket >= 0 && (!serialized.endsWith("]") || serialized.indexOf('[', bracket + 1) >= 0)) {
            throw new IllegalArgumentException("Invalid vanilla block state " + serialized);
        }
        var id = Identifier.parse(idText);
        if (!id.getNamespace().equals("minecraft") || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalArgumentException("Unknown vanilla block carrier " + id);
        }
        BlockState state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        if (bracket >= 0) {
            String values = serialized.substring(bracket + 1, serialized.length() - 1);
            if (values.isBlank()) {
                throw new IllegalArgumentException("Empty property list in " + serialized);
            }
            var seen = new java.util.HashSet<String>();
            for (var pair : values.split(",", -1)) {
                int equals = pair.indexOf('=');
                if (equals <= 0 || equals == pair.length() - 1 || !seen.add(pair.substring(0, equals))) {
                    throw new IllegalArgumentException("Invalid property in vanilla state " + serialized);
                }
                String propertyName = pair.substring(0, equals);
                String propertyValue = pair.substring(equals + 1);
                var property = state.getProperties().stream()
                        .filter(candidate -> candidate.getName().equals(propertyName)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown property " + propertyName + " in " + serialized));
                state = setProperty(state, property, propertyValue, serialized);
            }
        }
        if (state.hasBlockEntity()
                || !Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
                CollisionContext.empty()))
                || !Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
                CollisionContext.empty()))) {
            throw new IllegalArgumentException("Vanilla fallback must be a block-entity-free full cube: " + serialized);
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setProperty(BlockState state, Property<T> property,
                                                                     String value, String serialized) {
        var parsed = property.getValue(value).orElseThrow(() -> new IllegalArgumentException(
                "Invalid value " + value + " for property " + property.getName() + " in " + serialized));
        return state.setValue(property, parsed);
    }

    private static String stateKey(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (state.getProperties().isEmpty()) {
            return id;
        }
        var values = state.getProperties().stream().sorted((left, right) -> left.getName().compareTo(right.getName()))
                .map(property -> property.getName() + "=" + propertyValue(state, property)).toList();
        return id + "[" + String.join(",", values) + "]";
    }

    private static boolean isAdministratorOverride(MappingDecision decision) {
        return "administrator-forced-profile".equals(decision.provider());
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private static String modelHash(MappingDecision decision, String modelKind) {
        var id = Identifier.parse(decision.descriptor().registryId());
        String relative = modelKind.equals("item")
                ? "assets/" + id.getNamespace() + "/items/" + id.getPath() + ".json"
                : "assets/" + id.getNamespace() + "/models/block/" + id.getPath() + ".json";
        var container = FabricLoader.getInstance().getModContainer(decision.descriptor().ownerMod());
        if (container.isPresent()) {
            for (var root : container.get().getRootPaths().stream().sorted().toList()) {
                var normalizedRoot = root.toAbsolutePath().normalize();
                var candidate = normalizedRoot.resolve(relative).normalize();
                if (!candidate.startsWith(normalizedRoot) || !Files.isRegularFile(candidate)) {
                    continue;
                }
                try {
                    long size = Files.size(candidate);
                    if (size > MAX_HASHED_MODEL_BYTES) {
                        throw new IllegalStateException("Mapped model exceeds safe hash limit: " + relative);
                    }
                    return sha256(Files.readAllBytes(candidate));
                } catch (IOException exception) {
                    throw new IllegalStateException("Unable to hash mapped model " + relative, exception);
                }
            }
        }
        return sha256(("unresolved-model:" + relative).getBytes(StandardCharsets.UTF_8));
    }

    private static boolean itemDefinitionExists(MappingDecision decision) {
        var id = Identifier.parse(decision.descriptor().registryId());
        String relative = "assets/" + id.getNamespace() + "/items/" + id.getPath() + ".json";
        var container = FabricLoader.getInstance().getModContainer(decision.descriptor().ownerMod());
        if (container.isEmpty()) {
            return false;
        }
        for (var root : container.get().getRootPaths().stream().sorted().toList()) {
            var normalizedRoot = root.toAbsolutePath().normalize();
            var candidate = normalizedRoot.resolve(relative).normalize();
            if (candidate.startsWith(normalizedRoot) && Files.isRegularFile(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM has no SHA-256", impossible);
        }
    }
}
