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
import io.github.theepicblock.polymc.api.item.ItemPoly;
import io.github.theepicblock.polymc.api.item.ItemTransformer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Legacy registrations collected during initialization and frozen into the Reborn plan. */
public final class PolyRegistry {
    private final Map<Item, ItemPoly> itemPolys = new IdentityHashMap<>();
    private final List<ItemTransformer> globalItemPolys = new ArrayList<>();
    private final Map<Block, BlockPoly> blockPolys = new IdentityHashMap<>();
    private final Map<MenuType<?>, GuiPoly> guiPolys = new IdentityHashMap<>();
    private final Map<EntityType<?>, EntityPoly<?>> entityPolys = new IdentityHashMap<>();
    private boolean frozen;

    public synchronized void registerItemPoly(Item item, ItemPoly poly) {
        checkMutable();
        itemPolys.put(item, poly);
    }

    public synchronized void registerGlobalItemPoly(ItemTransformer poly) {
        checkMutable();
        globalItemPolys.add(poly);
    }

    public synchronized void registerBlockPoly(Block block, BlockPoly poly) {
        checkMutable();
        blockPolys.put(block, poly);
    }

    public synchronized void registerGuiPoly(MenuType<?> screenHandler, GuiPoly poly) {
        checkMutable();
        guiPolys.put(screenHandler, poly);
    }

    public synchronized <T extends Entity> void registerEntityPoly(EntityType<T> entityType, EntityPoly<T> poly) {
        checkMutable();
        entityPolys.put(entityType, poly);
    }

    public synchronized boolean hasItemPoly(Item item) {
        return itemPolys.containsKey(item);
    }

    public synchronized boolean hasGlobalItemPolys() {
        return !globalItemPolys.isEmpty();
    }

    public synchronized boolean hasBlockPoly(Block block) {
        return blockPolys.containsKey(block);
    }

    public synchronized boolean hasGuiPoly(MenuType<?> menu) {
        return guiPolys.containsKey(menu);
    }

    public synchronized boolean hasEntityPoly(EntityType<?> type) {
        return entityPolys.containsKey(type);
    }

    public synchronized PolyMap build() {
        frozen = true;
        return new PolyMap(itemPolys, globalItemPolys, blockPolys, guiPolys, entityPolys);
    }

    public synchronized Map<Item, ItemPoly> itemPolys() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(itemPolys));
    }

    public synchronized List<ItemTransformer> globalItemPolys() {
        return List.copyOf(globalItemPolys);
    }

    public synchronized Map<Block, BlockPoly> blockPolys() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(blockPolys));
    }

    public synchronized Map<MenuType<?>, GuiPoly> guiPolys() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(guiPolys));
    }

    public synchronized Map<EntityType<?>, EntityPoly<?>> entityPolys() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(entityPolys));
    }

    private void checkMutable() {
        if (frozen) {
            throw new IllegalStateException("Legacy PolyRegistry is frozen");
        }
    }
}
