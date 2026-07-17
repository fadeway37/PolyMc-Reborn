/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/** Full-cube projection; server state and mechanics remain on the original block. */
public final class PlannedBlockOverlay implements PolymerTexturedBlock {
    private final BlockState clientState;

    public PlannedBlockOverlay(BlockState clientState) {
        this.clientState = clientState;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return clientState;
    }
}
