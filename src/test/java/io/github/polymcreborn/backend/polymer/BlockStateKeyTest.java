/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NoteBlock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStateKeyTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void canonicalPropertiesAreSortedAndStable() {
        var state = Blocks.NOTE_BLOCK.defaultBlockState()
                .setValue(NoteBlock.NOTE, 2).setValue(NoteBlock.POWERED, true);

        assertEquals("instrument=harp,note=2,powered=true", BlockStateKey.canonicalProperties(state));
        assertEquals(BlockStateKey.canonicalProperties(state), BlockStateKey.canonicalProperties(state));
    }

    @Test
    void everyStateHasAUniqueDeterministicallySortedCanonicalKey() {
        var states = Blocks.NOTE_BLOCK.getStateDefinition().getPossibleStates();
        var first = states.stream().map(BlockStateKey::canonicalProperties).sorted().toList();
        var second = states.reversed().stream().map(BlockStateKey::canonicalProperties).sorted().toList();

        assertEquals(first, second);
        assertEquals(states.size(), first.stream().distinct().count());
        assertTrue(first.getFirst().startsWith("instrument="));
    }
}
