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
import io.github.polymcreborn.pack.PlayerPackStateService;
import net.minecraft.world.level.block.Blocks;

/** Full-cube projection; server state and mechanics remain on the original block. */
public final class PlannedBlockOverlay implements PolymerTexturedBlock {
    private final Map<BlockState, BlockState> clientStates;
    private final PlayerPackStateService packStates;

    public PlannedBlockOverlay(Map<BlockState, BlockState> clientStates) {
        this(clientStates, null);
    }

    public PlannedBlockOverlay(Map<BlockState, BlockState> clientStates,
                               PlayerPackStateService packStates) {
        if (clientStates.isEmpty()) {
            throw new IllegalArgumentException("A planned block overlay requires at least one frozen state");
        }
        var immutable = new IdentityHashMap<BlockState, BlockState>();
        clientStates.forEach((server, client) -> immutable.put(
                Objects.requireNonNull(server, "server state"), Objects.requireNonNull(client, "client state")));
        this.clientStates = Collections.unmodifiableMap(immutable);
        this.packStates = packStates;
    }

    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        if (packStates != null && !packStates.customResourcesAllowed(context)) {
            return Blocks.STONE.defaultBlockState();
        }
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
