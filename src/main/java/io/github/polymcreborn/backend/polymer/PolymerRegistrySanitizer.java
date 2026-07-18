/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.other.PolymerMenuUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.mapping.MappingPlan;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

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
