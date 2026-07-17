/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import io.github.theepicblock.polymc.api.PolyMcEntrypoint;
import io.github.theepicblock.polymc.api.PolyRegistry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Proves a 26.1.2-recompiled extension can still use the legacy polymc entrypoint key. */
public final class LegacyFixtureEntrypoint implements PolyMcEntrypoint {
    @Override
    public void registerPolys(PolyRegistry registry) {
        FixtureContent.bootstrap();
        registry.registerItemPoly(FixtureContent.LEGACY_ITEM,
                (input, player, location) -> new ItemStack(Items.EMERALD, input.getCount()));
        registry.registerBlockPoly(FixtureContent.LEGACY_BLOCK,
                state -> Blocks.GOLD_BLOCK.defaultBlockState());
    }
}
