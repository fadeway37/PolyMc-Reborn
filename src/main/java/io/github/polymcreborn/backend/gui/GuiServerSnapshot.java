/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Deep immutable snapshot used only around a click; it is never an inventory authority. */
public final class GuiServerSnapshot {
    private final int containerId;
    private final int stateId;
    private final List<ItemStack> slots;
    private final ItemStack carried;

    public GuiServerSnapshot(int containerId, int stateId, List<ItemStack> slots, ItemStack carried) {
        if (containerId < 0 || stateId < 0) {
            throw new IllegalArgumentException("containerId and stateId must be non-negative");
        }
        Objects.requireNonNull(slots, "slots");
        List<ItemStack> copies = new ArrayList<>(slots.size());
        slots.forEach(stack -> copies.add(Objects.requireNonNull(stack, "slot stack").copy()));
        this.containerId = containerId;
        this.stateId = stateId;
        this.slots = List.copyOf(copies);
        this.carried = Objects.requireNonNull(carried, "carried").copy();
    }

    public static GuiServerSnapshot capture(AbstractContainerMenu menu) {
        Objects.requireNonNull(menu, "menu");
        return new GuiServerSnapshot(menu.containerId, menu.getStateId(), menu.getItems(),
                menu.getCarried());
    }

    public int containerId() {
        return containerId;
    }

    public int stateId() {
        return stateId;
    }

    public int slotCount() {
        return slots.size();
    }

    public ItemStack slot(int slot) {
        return slots.get(slot).copy();
    }

    public ItemStack carried() {
        return carried.copy();
    }
}
