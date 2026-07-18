/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockStateModelResolverTest {
    private static final Identifier BLOCK_ID = Identifier.parse("polymc-reborn:unit_missing_variants");

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void multiStateBlockWithoutBlockstateVariantsFailsBeforeCarrierAllocation() {
        var descriptor = ContentDescriptor.of(BLOCK_ID.toString(), "polymc-reborn", ContentType.BLOCK,
                Map.of("state_count", "2"));

        var failure = assertThrows(BlockStateModelResolver.ResolutionException.class,
                () -> new BlockStateModelResolver().resolve(descriptor, Blocks.REDSTONE_LAMP));

        assertTrue(failure.getMessage().contains("Missing required state variants"));
        assertTrue(failure.getMessage().contains("for 2 safe server states"));
    }
}
