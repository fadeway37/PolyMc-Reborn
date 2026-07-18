/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlannedBlockOverlayTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void performsConstantTimeStateSpecificLookupAndFailsClosedForUnknownState() {
        var off = Blocks.REDSTONE_LAMP.defaultBlockState();
        var on = off.setValue(net.minecraft.world.level.block.RedstoneLampBlock.LIT, true);
        var stone = Blocks.STONE.defaultBlockState();
        var gold = Blocks.GOLD_BLOCK.defaultBlockState();
        var overlay = new PlannedBlockOverlay(Map.of(off, stone, on, gold));

        assertEquals(stone, overlay.getPolymerBlockState(off, null));
        assertEquals(gold, overlay.getPolymerBlockState(on, null));
        assertEquals(2, overlay.mappedStateCount());
        assertThrows(IllegalStateException.class,
                () -> overlay.getPolymerBlockState(Blocks.DIRT.defaultBlockState(), null));
    }
}
