/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.upgradeexpansionfixture;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Independent Mod B used only by the mod-set expansion production test. */
public final class UpgradeExpansionFixture implements ModInitializer {
    private static final String MOD_ID = "polymc-reborn-upgrade-expansion";

    @Override
    public void onInitialize() {
        registerItem("expanded_item");
        registerBlock("expanded_block");
    }

    private static void registerItem(String path) {
        Identifier id = id(path);
        var properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
        Registry.register(BuiltInRegistries.ITEM, id, new Item(properties));
    }

    private static void registerBlock(String path) {
        Identifier id = id(path);
        var block = Registry.register(BuiltInRegistries.BLOCK, id,
                new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.EMERALD_BLOCK)
                        .setId(ResourceKey.create(Registries.BLOCK, id))));
        var properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
                .useBlockDescriptionPrefix();
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, properties));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }
}
