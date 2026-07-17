/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.legacy;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.mapping.MappingPlan;
import io.github.theepicblock.polymc.api.PolyMap;
import io.github.theepicblock.polymc.api.PolyRegistry;
import net.minecraft.core.registries.BuiltInRegistries;

/** Applies only explicit legacy item/block registrations; entity and GUI entries stay diagnostic-only. */
public final class LegacyPolymerBackend {
    private final PolyRegistry registry;
    private final PolyMap map;

    public LegacyPolymerBackend(PolyRegistry registry) {
        this.registry = registry;
        this.map = registry.build();
    }

    public void apply(MappingPlan plan) {
        for (var decision : plan.orderedDecisions()) {
            if (decision.status() != MappingStatus.LEGACY) {
                continue;
            }
            if (decision.descriptor().contentType() == ContentType.ITEM) {
                var item = BuiltInRegistries.ITEM.getValue(
                        net.minecraft.resources.Identifier.parse(decision.descriptor().registryId()));
                if (!(PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, item) instanceof PolymerItem)) {
                    PolymerItemUtils.registerOverlay(item, new LegacyItemOverlay(map,
                            net.minecraft.resources.Identifier.parse(decision.descriptor().registryId())));
                }
            } else if (decision.descriptor().contentType() == ContentType.BLOCK) {
                var block = BuiltInRegistries.BLOCK.getValue(
                        net.minecraft.resources.Identifier.parse(decision.descriptor().registryId()));
                var blockPoly = registry.blockPolys().get(block);
                if (blockPoly != null && !(PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block)
                        instanceof PolymerBlock)) {
                    PolymerBlockUtils.registerOverlay(block, new LegacyBlockOverlay(blockPoly));
                }
            }
        }
    }
}
