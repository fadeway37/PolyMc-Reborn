/*
 * PolyMc
 * Copyright (C) 2020-2020 TheEpicBlock_TEB
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; If not, see <https://www.gnu.org/licenses>.
 */
package io.github.theepicblock.polymc.api;

import io.github.theepicblock.polymc.api.block.BlockPoly;
import io.github.theepicblock.polymc.api.entity.EntityPoly;
import io.github.theepicblock.polymc.api.gui.GuiPoly;
import io.github.theepicblock.polymc.api.item.ItemLocation;
import io.github.theepicblock.polymc.api.item.ItemPoly;
import io.github.theepicblock.polymc.api.item.ItemTransformer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/** Immutable legacy lookup concept. Unsafe creative reversal is intentionally absent. */
public final class PolyMap {
    private final Map<Item, ItemPoly> items;
    private final List<ItemTransformer> globals;
    private final Map<Block, BlockPoly> blocks;
    private final Map<MenuType<?>, GuiPoly> guis;
    private final Map<EntityType<?>, EntityPoly<?>> entities;

    PolyMap(Map<Item, ItemPoly> items, List<ItemTransformer> globals, Map<Block, BlockPoly> blocks,
            Map<MenuType<?>, GuiPoly> guis, Map<EntityType<?>, EntityPoly<?>> entities) {
        this.items = Map.copyOf(items);
        this.globals = List.copyOf(globals);
        this.blocks = Map.copyOf(blocks);
        this.guis = Map.copyOf(guis);
        this.entities = Map.copyOf(entities);
    }

    public ItemStack getClientItem(ItemStack serverItem, @Nullable ServerPlayer player,
                                   @Nullable ItemLocation location) {
        var itemPoly = items.get(serverItem.getItem());
        ItemStack client = itemPoly == null ? serverItem.copy() : itemPoly.getClientItem(serverItem.copy(), player, location);
        for (var transformer : globals) {
            client = transformer.transform(serverItem, client, this, player, location);
        }
        return client;
    }

    public BlockState getClientState(BlockState state, @Nullable ServerPlayer player) {
        var poly = blocks.get(state.getBlock());
        return poly == null ? state : poly.getClientBlock(state);
    }

    public ItemPoly getItemPoly(Item item) {
        return items.get(item);
    }

    public BlockPoly getBlockPoly(Block block) {
        return blocks.get(block);
    }

    public GuiPoly getGuiPoly(MenuType<?> menu) {
        return guis.get(menu);
    }

    @SuppressWarnings("unchecked")
    public <T extends Entity> EntityPoly<T> getEntityPoly(EntityType<T> type) {
        return (EntityPoly<T>) entities.get(type);
    }

    public boolean isVanillaLikeMap() {
        return true;
    }
}
