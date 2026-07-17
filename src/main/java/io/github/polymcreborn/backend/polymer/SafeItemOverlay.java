/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Automatic item projection that never invokes Polymer's unsigned full-stack reverse payload. */
public final class SafeItemOverlay implements PolymerItem {
    private volatile Item carrier;
    private final Identifier model;

    public SafeItemOverlay(Item carrier, Identifier model) {
        this.carrier = carrier;
        this.model = model;
    }

    void setCarrier(Item resolvedCarrier) {
        this.carrier = java.util.Objects.requireNonNull(resolvedCarrier, "resolvedCarrier");
    }

    @Override
    public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
        return carrier;
    }

    @Override
    public Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider lookup) {
        return model;
    }

    @Override
    public ItemStack getPolymerItemStack(ItemStack input, TooltipFlag tooltipType, PacketContext context,
                                         HolderLookup.Provider lookup) {
        int maxStack = carrier.components().getOrDefault(DataComponents.MAX_STACK_SIZE, 64);
        var output = new ItemStack(carrier, Math.max(1, Math.min(input.getCount(), maxStack)));

        copy(DataComponents.CUSTOM_NAME, input, output);
        copy(DataComponents.ITEM_NAME, input, output);
        copy(DataComponents.LORE, input, output);
        copy(DataComponents.RARITY, input, output);
        copy(DataComponents.DYED_COLOR, input, output);
        copy(DataComponents.UNBREAKABLE, input, output);
        copy(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, input, output);
        if (model != null) {
            output.set(DataComponents.ITEM_MODEL, model);
        }

        Integer maxDamage = input.get(DataComponents.MAX_DAMAGE);
        Integer damage = input.get(DataComponents.DAMAGE);
        if (maxDamage != null && maxDamage > 0) {
            output.setCount(1);
            output.set(DataComponents.MAX_STACK_SIZE, 1);
            output.set(DataComponents.MAX_DAMAGE, maxDamage);
            output.set(DataComponents.DAMAGE, Math.max(0, Math.min(damage == null ? 0 : damage, maxDamage)));
        }

        var enchantments = input.get(DataComponents.ENCHANTMENTS);
        if (enchantments != null && !enchantments.isEmpty()) {
            output.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        }
        return output;
    }

    private static <T> void copy(DataComponentType<T> type, ItemStack input, ItemStack output) {
        T value = input.get(type);
        if (value != null) {
            output.set(type, value);
        }
    }
}
