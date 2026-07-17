/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.legacy;

import eu.pb4.polymer.core.api.item.PolymerItem;
import io.github.polymcreborn.backend.polymer.SafeItemOverlay;
import io.github.theepicblock.polymc.api.PolyMap;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Sanitizes trusted legacy output and never emits a custom registry Item to vanilla clients. */
final class LegacyItemOverlay implements PolymerItem {
    private final PolyMap map;
    private final Identifier serverId;

    LegacyItemOverlay(PolyMap map, Identifier serverId) {
        this.map = map;
        this.serverId = serverId;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return safeClient(stack).getItem();
    }

    @Override
    public ItemStack getPolymerItemStack(ItemStack stack, TooltipFlag tooltipType, PacketContext context,
                                         HolderLookup.Provider lookup) {
        var legacy = safeClient(stack);
        return new SafeItemOverlay(legacy.getItem(), serverId)
                .getPolymerItemStack(legacy, tooltipType, context, lookup);
    }

    private ItemStack safeClient(ItemStack stack) {
        ItemStack candidate = map.getClientItem(stack.copy(), null, null);
        if (candidate == null || candidate.isEmpty()) {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("paper")),
                    Math.max(1, stack.getCount()));
        }
        var id = BuiltInRegistries.ITEM.getKey(candidate.getItem());
        if (!id.getNamespace().equals("minecraft")) {
            return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("paper")),
                    Math.max(1, stack.getCount()));
        }
        return candidate;
    }
}
