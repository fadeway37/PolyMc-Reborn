/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.other.PolymerMenuUtils;
import eu.pb4.polymer.core.api.other.PolymerComponent;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import eu.pb4.polymer.rsm.api.RegistrySyncUtils;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.mapping.MappingPlan;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Hides custom block-entity type ids from vanilla registry synchronization.
 * Their payload is still filtered by Polymer and the real server block entity
 * remains authoritative.
 */
public final class PolymerRegistrySanitizer {
    private PolymerRegistrySanitizer() {
    }

    public static List<Identifier> registerServerOnlyBlockEntityTypes() {
        var registered = new ArrayList<Identifier>();
        BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet().stream().sorted().forEach(id -> {
            if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
                return;
            }
            var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id);
            if (!PolymerBlockUtils.isPolymerBlockEntityType(type)) {
                PolymerBlockUtils.registerBlockEntity(type);
                registered.add(id);
            }
        });
        return List.copyOf(registered);
    }

    /**
     * Marks every non-vanilla data-component type as server-only and filters it
     * from stacks sent to clients that do not understand it. The real component
     * remains attached to the authoritative server stack. This must run before
     * Fabric constructs its login registry-sync payload.
     */
    public static List<Identifier> registerServerOnlyDataComponentTypes() {
        var registered = new ArrayList<Identifier>();
        BuiltInRegistries.DATA_COMPONENT_TYPE.keySet().stream().sorted().forEach(id -> {
            if (id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
                return;
            }
            var type = BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(id);
            if (!PolymerComponent.isPolymerComponent(type)) {
                PolymerComponent.registerDataComponent(type);
                registered.add(id);
            }
        });
        return List.copyOf(registered);
    }

    /**
     * Removes every remaining non-vanilla static registry entry from Fabric's
     * vanilla-client registry view. Polymer-specific item, block, entity, menu,
     * component, and block-entity registration runs first; this final pass only
     * marks entries which no backend has already marked server-only.
     *
     * <p>The entries remain present and authoritative in every server registry.
     * Registry Sync Manipulator filters only Fabric's login payload. This is
     * necessary for auxiliary content such as custom recipe serializers,
     * particles, sounds, and statistics that a server-only content mod may
     * register but a vanilla client cannot decode.</p>
     */
    public static RegistrySanitization registerServerOnlyStaticEntries() {
        var entriesByRegistry = new TreeMap<Identifier, List<Identifier>>();
        BuiltInRegistries.REGISTRY.keySet().stream().sorted().forEach(registryId -> {
            var registry = BuiltInRegistries.REGISTRY.getValue(registryId);
            var hidden = registerServerOnlyEntries(registry);
            if (!hidden.isEmpty()) {
                entriesByRegistry.put(registryId, hidden);
            }
        });
        return new RegistrySanitization(entriesByRegistry);
    }

    private static <T> List<Identifier> registerServerOnlyEntries(Registry<T> registry) {
        var registered = new ArrayList<Identifier>();
        registry.keySet().stream().sorted().forEach(id -> {
            if (isVanillaNamespace(id)) {
                return;
            }
            if (!RegistrySyncUtils.isServerEntry(registry, id)) {
                RegistrySyncUtils.setServerEntry(registry, id);
            }
            registered.add(id);
        });
        return List.copyOf(registered);
    }

    static boolean isVanillaNamespace(Identifier id) {
        return id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
                || id.getNamespace().equals("brigadier");
    }

    /** Stable evidence describing all entries hidden from vanilla registry sync. */
    public record RegistrySanitization(Map<Identifier, List<Identifier>> entriesByRegistry) {
        public RegistrySanitization {
            var copy = new TreeMap<Identifier, List<Identifier>>();
            entriesByRegistry.forEach((registry, entries) ->
                    copy.put(registry, entries.stream().sorted().toList()));
            entriesByRegistry = Collections.unmodifiableMap(copy);
        }

        public int totalEntries() {
            return entriesByRegistry.values().stream().mapToInt(List::size).sum();
        }

        public java.util.Set<String> hiddenIdentifiers() {
            var identifiers = new java.util.TreeSet<String>();
            entriesByRegistry.values().forEach(entries -> entries.forEach(id ->
                    identifiers.add(id.toString())));
            return Collections.unmodifiableSet(identifiers);
        }
    }

    /**
     * Gives unsupported custom blocks a fixed fail-closed wire representation.
     * The compatibility decision deliberately remains UNSUPPORTED/ERROR: a
     * barrier quarantine is not a claim that geometry or behavior is supported.
     */
    public static MappingPlan quarantineUnsupportedBlocks(MappingPlan plan) {
        var decisions = new ArrayList<MappingDecision>(plan.size());
        for (MappingDecision decision : plan.orderedDecisions()) {
            if (decision.descriptor().contentType() != ContentType.BLOCK
                    || (decision.status() != MappingStatus.UNSUPPORTED
                    && decision.status() != MappingStatus.ERROR)) {
                decisions.add(decision);
                continue;
            }
            var id = Identifier.parse(decision.descriptor().registryId());
            var block = BuiltInRegistries.BLOCK.getValue(id);
            if (!(PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block)
                    instanceof PolymerBlock)) {
                PolymerBlockUtils.registerOverlay(block,
                        (state, context) -> Blocks.BARRIER.defaultBlockState());
            }
            var reasons = new ArrayList<>(decision.reasonChain());
            reasons.add("Unsupported block packets are quarantined to a vanilla barrier state; "
                    + "server geometry and behavior remain authoritative");
            var warnings = new ArrayList<>(decision.warnings());
            warnings.add("Barrier is a registry-safety quarantine, not a supported visual or geometry mapping");
            decisions.add(new MappingDecision(decision.descriptor(), decision.status(), decision.provider(),
                    "polymer-quarantine", "unsupported-barrier-quarantine", "minecraft:barrier",
                    decision.confidence(), 100, reasons, decision.resourceDependencies(), warnings,
                    decision.failureReason()));
        }
        return plan.replaceDecisions(decisions);
    }

    /**
     * Keeps unsupported custom entity/menu registry ids off vanilla registry
     * sync. Entities use Polymer's invisible marker quarantine; menu types are
     * server-only and remain unusable until an explicit projection is present.
     * Neither quarantine changes the UNSUPPORTED/ERROR compatibility status.
     */
    public static MappingPlan quarantineUnsupportedEntityAndMenuTypes(MappingPlan plan) {
        var decisions = new ArrayList<MappingDecision>(plan.size());
        for (MappingDecision decision : plan.orderedDecisions()) {
            if (decision.status() != MappingStatus.UNSUPPORTED
                    && decision.status() != MappingStatus.ERROR) {
                decisions.add(decision);
                continue;
            }
            if (decision.descriptor().contentType() == ContentType.ENTITY) {
                var id = Identifier.parse(decision.descriptor().registryId());
                var type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
                if (!PolymerEntityUtils.isPolymerEntityType(type)) {
                    PolymerEntityUtils.registerType(type);
                }
                decisions.add(quarantined(decision, "unsupported-marker-entity-quarantine",
                        "minecraft:marker",
                        "Unsupported entity registry sync is quarantined to Polymer's invisible marker type; "
                                + "the real server entity remains authoritative and has no client interaction proxy",
                        "Marker quarantine prevents registry leakage but is not a supported entity projection"));
                continue;
            }
            if (decision.descriptor().contentType() == ContentType.GUI) {
                var id = Identifier.parse(decision.descriptor().registryId());
                var type = BuiltInRegistries.MENU.getValue(id);
                if (!PolymerMenuUtils.isPolymerType(type)) {
                    PolymerMenuUtils.registerType(type);
                }
                decisions.add(quarantined(decision, "unsupported-server-only-menu-quarantine", "none",
                        "Unsupported menu registry sync is marked server-only; opening it to a vanilla client "
                                + "remains disabled until an explicit standard-container adapter is registered",
                        "Server-only quarantine prevents registry leakage but does not project this GUI"));
                continue;
            }
            decisions.add(decision);
        }
        return plan.replaceDecisions(decisions);
    }

    private static MappingDecision quarantined(MappingDecision decision, String strategy, String carrier,
                                               String reason, String warning) {
        var reasons = new ArrayList<>(decision.reasonChain());
        reasons.add(reason);
        var warnings = new ArrayList<>(decision.warnings());
        warnings.add(warning);
        return new MappingDecision(decision.descriptor(), decision.status(), decision.provider(),
                "polymer-quarantine", strategy, carrier, decision.confidence(), 100, reasons,
                decision.resourceDependencies(), warnings, decision.failureReason());
    }
}
