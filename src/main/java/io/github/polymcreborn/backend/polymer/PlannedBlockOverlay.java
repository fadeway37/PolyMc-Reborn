/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/** Full-cube projection; server state and mechanics remain on the original block. */
public final class PlannedBlockOverlay implements PolymerTexturedBlock {
    private final Map<BlockState, BlockState> clientStates;

    public PlannedBlockOverlay(Map<BlockState, BlockState> clientStates) {
        if (clientStates.isEmpty()) {
            throw new IllegalArgumentException("A planned block overlay requires at least one frozen state");
        }
        var immutable = new IdentityHashMap<BlockState, BlockState>();
        clientStates.forEach((server, client) -> immutable.put(
                Objects.requireNonNull(server, "server state"), Objects.requireNonNull(client, "client state")));
        this.clientStates = Collections.unmodifiableMap(immutable);
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        BlockState projected = clientStates.get(state);
        if (projected == null) {
            throw new IllegalStateException("No frozen Polymer projection for server state "
                    + BlockStateKey.canonicalProperties(state));
        }
        return projected;
    }

    public int mappedStateCount() {
        return clientStates.size();
    }
}
