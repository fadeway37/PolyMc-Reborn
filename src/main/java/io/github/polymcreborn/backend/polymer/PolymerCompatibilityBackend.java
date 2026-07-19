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
import io.github.polymcreborn.mapping.MappingPlanDiff;
import io.github.polymcreborn.mapping.MappingStoreDocument;
import io.github.polymcreborn.mapping.PersistentMappingStore;
import io.github.polymcreborn.mapping.StoredMapping;
import io.github.polymcreborn.pack.PlayerPackStateService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Applies the frozen plan using public Polymer overlays and the shared Polymer Blocks pool. */
public final class PolymerCompatibilityBackend {
    private static final long MAX_HASHED_MODEL_BYTES = 8_388_608L;
    private final PersistentMappingStore store;
    private final BoundedDiagnosticCollector diagnostics;
    private final boolean persistentMappings;
    private final boolean customModelsEnabled;
    private final PlayerPackStateService packStates;
    private final Map<String, SafeItemOverlay> pendingSemanticItems = new TreeMap<>();
    private volatile MappingStoreDocument startupBase = MappingStoreDocument.empty();
    private volatile MappingStoreDocument mappingSnapshot = MappingStoreDocument.empty();
    private volatile MappingPlanDiff startupDiff = MappingPlanDiff.compare(
            MappingStoreDocument.empty(), MappingStoreDocument.empty());

    public PolymerCompatibilityBackend(PersistentMappingStore store, BoundedDiagnosticCollector diagnostics) {
        this(store, diagnostics, true, true);
    }

    public PolymerCompatibilityBackend(PersistentMappingStore store, BoundedDiagnosticCollector diagnostics,
                                       boolean persistentMappings) {
        this(store, diagnostics, persistentMappings, true);
    }

    public PolymerCompatibilityBackend(PersistentMappingStore store, BoundedDiagnosticCollector diagnostics,
                                       boolean persistentMappings, boolean customModelsEnabled) {
        this(store, diagnostics, persistentMappings, customModelsEnabled, null);
    }

    public PolymerCompatibilityBackend(PersistentMappingStore store, BoundedDiagnosticCollector diagnostics,
                                       boolean persistentMappings, boolean customModelsEnabled,
                                       PlayerPackStateService packStates) {
        this.store = store;
        this.diagnostics = diagnostics;
        this.persistentMappings = persistentMappings;
        this.customModelsEnabled = customModelsEnabled;
        this.packStates = packStates;
    }

    public MappingPlan apply(MappingPlan proposedPlan, String projectVersion) {
        pendingSemanticItems.clear();
        MappingStoreDocument existing = persistentMappings ? store.load() : MappingStoreDocument.empty();
        startupBase = existing;
        var updated = new LinkedHashMap<String, MappingDecision>();
        proposedPlan.orderedDecisions().forEach(decision -> updated.put(decision.descriptor().key(), decision));
        var newMappings = new ArrayList<StoredMapping>();
        var retiredMappings = new HashSet<String>();

        applyItems(proposedPlan, existing, updated, newMappings, projectVersion);
        applyBlocks(proposedPlan, existing, updated, newMappings, retiredMappings, projectVersion);

        MappingStoreDocument mergeBase = withoutRetiredMappings(existing, retiredMappings);
        var merged = store.mergePreservingAssignments(mergeBase, newMappings);
        mappingSnapshot = merged;
        startupDiff = MappingPlanDiff.compare(startupBase, merged);
        if (persistentMappings) {
            store.save(merged.mappings());
        }
        return proposedPlan.replaceDecisions(List.copyOf(updated.values()));
    }

    static MappingStoreDocument withoutRetiredMappings(MappingStoreDocument existing, Set<String> retiredMappings) {
        return retiredMappings.isEmpty() ? existing : new MappingStoreDocument(
                existing.schemaVersion(), existing.mappingAlgorithmVersion(), existing.mappings().stream()
                .filter(mapping -> !retiredMappings.contains(mapping.key())).toList());
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
                        customModelsEnabled && itemDefinitionExists(effective) ? serverId : null, packStates);
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
            var merged = store.mergePreservingAssignments(existing, resolvedMappings);
            store.save(merged.mappings());
            mappingSnapshot = merged;
            startupDiff = MappingPlanDiff.compare(startupBase, merged);
        } else if (!resolvedMappings.isEmpty()) {
            var merged = store.mergePreservingAssignments(mappingSnapshot, resolvedMappings);
            mappingSnapshot = merged;
            startupDiff = MappingPlanDiff.compare(startupBase, merged);
        }
        return current.replaceDecisions(List.copyOf(updated.values()));
    }

    public MappingStoreDocument mappingSnapshot() {
        return mappingSnapshot;
    }

    public MappingPlanDiff startupDiff() {
        return startupDiff;
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
                             Set<String> retiredMappings, String projectVersion) {
        var existingBlocks = new TreeMap<String, StoredMapping>();
        existing.mappings().stream().filter(mapping -> mapping.contentType() == ContentType.BLOCK)
                .sorted().forEach(mapping -> existingBlocks.put(mapping.key(), mapping));
        var applications = new ArrayList<BlockApplication>();
        var stateTargetsByStoredKey = new HashMap<String, StateApplication>();
        var modelResolver = new BlockStateModelResolver();
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
            try {
                var states = serverBlock.getStateDefinition().getPossibleStates().stream()
                        .sorted(Comparator.comparing(BlockStateKey::canonicalProperties)).toList();
                boolean needsTexturedModels = "textured-full-cube".equals(decision.strategy())
                        || existingBlocks.values().stream().anyMatch(mapping -> mapping.registryId().equals(registryId)
                        && mapping.strategy().equals("textured-full-cube"));
                Map<BlockState, BlockStateModelResolver.ResolvedModel> resolved = needsTexturedModels
                                ? modelResolver.resolve(decision.descriptor(), serverBlock) : Map.of();
                var stateApplications = new ArrayList<StateApplication>();
                var localStoredTargets = new HashMap<String, StateApplication>();
                var localRetiredMappings = new java.util.TreeSet<String>();
                for (BlockState serverState : states) {
                    String state = BlockStateKey.canonicalProperties(serverState);
                    StoredMapping persisted = existingBlocks.get(storedBlockKey(registryId, state));
                    String persistedKey = persisted == null ? null : persisted.key();
                    String storedState = state;
                    if (serverState == serverBlock.defaultBlockState() && !state.isEmpty()) {
                        StoredMapping legacy = existingBlocks.get(storedBlockKey(registryId, ""));
                        if (legacy != null) {
                            localRetiredMappings.add(legacy.key());
                            if (persisted == null) {
                                persisted = legacy;
                                persistedKey = legacy.key();
                            }
                        }
                    }
                    String strategy = persisted == null ? decision.strategy() : persisted.strategy();
                    BlockStateModelResolver.ResolvedModel stateModel = resolved.get(serverState);
                    if ("textured-full-cube".equals(strategy) && stateModel == null) {
                        throw new IllegalArgumentException("No resolved state model for " + registryId + "["
                                + state + "]");
                    }
                    if (!"textured-full-cube".equals(strategy)
                            && !"vanilla-fallback-state".equals(strategy)) {
                        throw new IllegalArgumentException("Unsupported Polymer block strategy " + strategy);
                    }
                    var application = new StateApplication(registryId, serverState, state, storedState,
                            strategy, persisted, stateModel);
                    stateApplications.add(application);
                    if (persistedKey != null && localStoredTargets.put(persistedKey, application) != null) {
                        throw new IllegalArgumentException("Stored mapping " + persistedKey
                                + " targets more than one server state");
                    }
                }
                localStoredTargets.forEach((key, value) -> {
                    if (stateTargetsByStoredKey.put(key, value) != null) {
                        throw new IllegalArgumentException("Stored mapping " + key
                                + " targets more than one registered block");
                    }
                });
                applications.add(new BlockApplication(decision, serverBlock, stateApplications,
                        localRetiredMappings));
            } catch (RuntimeException exception) {
                updated.put(decision.descriptor().key(), errorDecision(decision,
                        "Block state preparation failed: " + exception.getMessage()));
            }
        }

        var pooledExisting = existingBlocks.values().stream()
                .filter(mapping -> mapping.strategy().equals("textured-full-cube"))
                .toList();
        var newPooled = applications.stream().flatMap(application -> application.states().stream())
                .filter(state -> state.persisted() == null && state.strategy().equals("textured-full-cube"))
                .sorted().toList();
        int remaining = PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.FULL_BLOCK);
        if ((long) pooledExisting.size() + newPooled.size() > remaining) {
            failApplications(applications, updated, "Polymer FULL_BLOCK capacity exhausted before state allocation: "
                    + (pooledExisting.size() + newPooled.size()) + " required, " + remaining + " available");
            return;
        }

        var replayed = new HashMap<String, BlockState>();
        try {
            var carrierOrder = fullBlockCarrierOrder();
            var seenRanks = new HashSet<Integer>();
            for (StoredMapping mapping : pooledExisting) {
                Integer rank = carrierOrder.get(mapping.clientCarrier());
                if (rank == null) {
                    throw new IllegalArgumentException("Persisted FULL_BLOCK carrier is outside the safely ordered "
                            + "pool: " + mapping.clientCarrier());
                }
                if (!seenRanks.add(rank)) {
                    throw new IllegalArgumentException("Duplicate persisted FULL_BLOCK carrier "
                            + mapping.clientCarrier());
                }
            }
            var orderedExisting = pooledExisting.stream()
                    .sorted(Comparator.comparingInt(mapping -> carrierOrder.get(mapping.clientCarrier()))).toList();
            for (StoredMapping persisted : orderedExisting) {
                StateApplication target = stateTargetsByStoredKey.get(persisted.key());
                List<PolymerBlockModel> models = target == null
                        ? List.of(conventionalModel(persisted.registryId())) : target.model().models();
                BlockState carrier = allocate(persisted.registryId(), persisted.state(), models);
                String actual = BlockStateKey.canonicalCarrier(carrier);
                if (!actual.equals(persisted.clientCarrier())) {
                    throw new IllegalArgumentException("Persisted carrier " + persisted.clientCarrier()
                            + " replayed as " + actual + "; refusing silent remap");
                }
                replayed.put(persisted.key(), carrier);
            }
            for (StateApplication state : newPooled) {
                state.assign(allocate(state.registryId(), state.state(), state.model().models()));
            }
        } catch (RuntimeException exception) {
            failApplications(applications, updated, "Deterministic block carrier replay failed: "
                    + exception.getMessage());
            return;
        }

        for (BlockApplication application : applications) {
            if (updated.get(application.decision().descriptor().key()).status() == MappingStatus.ERROR) {
                continue;
            }
            try {
                var overlayStates = new IdentityHashMap<BlockState, BlockState>();
                var dependencies = new java.util.TreeSet<String>();
                var preparedMappings = new ArrayList<StoredMapping>();
                for (StateApplication state : application.states()) {
                    BlockState carrier;
                    if (state.strategy().equals("textured-full-cube")) {
                        carrier = state.persisted() == null ? state.carrier() : replayed.get(state.persisted().key());
                        if (carrier == null) {
                            throw new IllegalArgumentException("Persisted state carrier was not replayed for "
                                    + state.registryId() + "[" + state.state() + "]");
                        }
                        dependencies.addAll(state.model().dependencies());
                    } else {
                        String serialized = state.persisted() == null
                                ? application.decision().clientCarrier() : state.persisted().clientCarrier();
                        carrier = parseVanillaState(serialized);
                        if (state.persisted() != null
                                && !BlockStateKey.canonicalCarrier(carrier).equals(state.persisted().clientCarrier())) {
                            throw new IllegalArgumentException("Persisted vanilla state is not canonical: "
                                    + state.persisted().clientCarrier());
                        }
                    }
                    overlayStates.put(state.serverState(), carrier);
                    String carrierKey = BlockStateKey.canonicalCarrier(carrier);
                    String resourceHash = state.model() == null
                            ? modelHash(application.decision(), "block") : state.model().sha256();
                    String createdWith = state.persisted() == null
                            ? projectVersion : state.persisted().createdWith();
                    preparedMappings.add(new StoredMapping(state.registryId(), ContentType.BLOCK,
                            state.storedState(), state.strategy(), carrierKey, resourceHash, createdWith,
                            projectVersion));
                }
                PolymerBlockUtils.registerOverlay(application.block(),
                        new PlannedBlockOverlay(overlayStates, packStates));
                mappings.addAll(preparedMappings);
                retiredMappings.addAll(application.retiredMappings());
                BlockState defaultCarrier = overlayStates.get(application.block().defaultBlockState());
                var applied = withStateAssignments(application.decision(), defaultCarrier,
                        application.states().size(), dependencies);
                updated.put(application.decision().descriptor().key(), customModelsEnabled ? applied
                        : withWarning(applied,
                        "Resource-pack generation is disabled; custom block textures are unavailable"));
            } catch (RuntimeException exception) {
                updated.put(application.decision().descriptor().key(), errorDecision(application.decision(),
                        "Block overlay registration failed: " + exception.getMessage()));
            }
        }
    }

    private BlockState allocate(String registryId, String state, List<PolymerBlockModel> models) {
        int remaining = PolymerBlockResourceUtils.getBlocksLeft(BlockModelType.FULL_BLOCK);
        if (remaining <= 0) {
            throw new MappingCapacityException(registryId + (state.isEmpty() ? "" : "[" + state + "]"),
                    "Polymer FULL_BLOCK", remaining);
        }
        var carrier = PolymerBlockResourceUtils.requestBlock(BlockModelType.FULL_BLOCK,
                models.toArray(PolymerBlockModel[]::new));
        if (carrier == null) {
            throw new MappingCapacityException(registryId + (state.isEmpty() ? "" : "[" + state + "]"),
                    "Polymer FULL_BLOCK", 0);
        }
        if (!fullBlockCarrierOrder().containsKey(BlockStateKey.canonicalCarrier(carrier))) {
            throw new MappingCapacityException(registryId + (state.isEmpty() ? "" : "[" + state + "]"),
                    "deterministically replayable Polymer FULL_BLOCK prefix", 0);
        }
        return carrier;
    }

    private static String storedBlockKey(String registryId, String state) {
        return "block:" + registryId + (state.isEmpty() ? "" : "[" + state + "]");
    }

    private static PolymerBlockModel conventionalModel(String registryId) {
        Identifier id = Identifier.parse(registryId);
        return PolymerBlockModel.of(Identifier.fromNamespaceAndPath(id.getNamespace(), "block/" + id.getPath()));
    }

    /**
     * Polymer exposes allocation and remaining capacity through public API, but not selection by persisted carrier.
     * The pinned FULL_BLOCK pool begins with these two vanilla state definitions. Staying inside that published,
     * reconstructable prefix lets replay select the original carrier order without reflection or Polymer internals.
     */
    private static Map<String, Integer> fullBlockCarrierOrder() {
        return FullBlockCarrierOrder.VALUE;
    }

    private static Map<String, Integer> createFullBlockCarrierOrder() {
        var order = new HashMap<String, Integer>();
        int rank = 0;
        for (Block block : List.of(Blocks.NOTE_BLOCK, Blocks.TARGET)) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (state != block.defaultBlockState()) {
                    order.put(BlockStateKey.canonicalCarrier(state), rank++);
                }
            }
        }
        return Map.copyOf(order);
    }

    private static final class FullBlockCarrierOrder {
        private static final Map<String, Integer> VALUE = createFullBlockCarrierOrder();

        private FullBlockCarrierOrder() {
        }
    }

    private void failApplications(List<BlockApplication> applications, Map<String, MappingDecision> updated,
                                  String failure) {
        for (BlockApplication application : applications) {
            updated.put(application.decision().descriptor().key(), errorDecision(application.decision(), failure));
        }
    }

    private static MappingDecision withStateAssignments(MappingDecision original, BlockState defaultCarrier,
                                                        int stateCount, Set<String> dependencies) {
        var reasons = new ArrayList<>(original.reasonChain());
        reasons.add(stateCount + " canonical server block state(s) received frozen O(1) carrier lookups");
        var resources = new ArrayList<>(original.resourceDependencies());
        resources.addAll(dependencies);
        var warnings = original.warnings().stream()
                .filter(warning -> !warning.contains("collapse visually equivalent block states"))
                .toList();
        return new MappingDecision(original.descriptor(), original.status(), original.provider(), original.backend(),
                original.strategy(), BlockStateKey.canonicalCarrier(defaultCarrier), original.confidence(),
                original.degradation(), reasons, resources, warnings, original.failureReason());
    }

    private record BlockApplication(MappingDecision decision, Block block, List<StateApplication> states,
                                    Set<String> retiredMappings) {
        private BlockApplication {
            states = List.copyOf(states);
            retiredMappings = Set.copyOf(retiredMappings);
        }
    }

    private static final class StateApplication implements Comparable<StateApplication> {
        private final String registryId;
        private final BlockState serverState;
        private final String state;
        private final String storedState;
        private final String strategy;
        private final StoredMapping persisted;
        private final BlockStateModelResolver.ResolvedModel model;
        private BlockState carrier;

        private StateApplication(String registryId, BlockState serverState, String state, String storedState,
                                 String strategy, StoredMapping persisted,
                                 BlockStateModelResolver.ResolvedModel model) {
            this.registryId = registryId;
            this.serverState = serverState;
            this.state = state;
            this.storedState = storedState;
            this.strategy = strategy;
            this.persisted = persisted;
            this.model = model;
        }

        private String registryId() {
            return registryId;
        }

        private BlockState serverState() {
            return serverState;
        }

        private String state() {
            return state;
        }

        private String storedState() {
            return storedState;
        }

        private String strategy() {
            return strategy;
        }

        private StoredMapping persisted() {
            return persisted;
        }

        private BlockStateModelResolver.ResolvedModel model() {
            return model;
        }

        private BlockState carrier() {
            return carrier;
        }

        private void assign(BlockState value) {
            if (carrier != null) {
                throw new IllegalStateException("Carrier already assigned for " + registryId + "[" + state + "]");
            }
            carrier = value;
        }

        @Override
        public int compareTo(StateApplication other) {
            int byRegistry = registryId.compareTo(other.registryId);
            return byRegistry != 0 ? byRegistry : state.compareTo(other.state);
        }
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

    private static boolean isAdministratorOverride(MappingDecision decision) {
        return "administrator-forced-profile".equals(decision.provider());
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
