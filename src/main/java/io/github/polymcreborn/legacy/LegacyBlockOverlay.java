/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.legacy;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import io.github.theepicblock.polymc.api.block.BlockPoly;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Legacy block adapter with a vanilla-registry fail-closed check. */
final class LegacyBlockOverlay implements PolymerBlock {
    private final BlockPoly poly;

    LegacyBlockOverlay(BlockPoly poly) {
        this.poly = poly;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        var candidate = poly.getClientBlock(state);
        if (candidate == null || !BuiltInRegistries.BLOCK.getKey(candidate.getBlock()).getNamespace().equals("minecraft")) {
            return Blocks.BARRIER.defaultBlockState();
        }
        return candidate;
    }
}
