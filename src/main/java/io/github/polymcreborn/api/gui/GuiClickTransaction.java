/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Untrusted client prediction accompanying one server-authoritative container click. */
public final class GuiClickTransaction {
    private final int containerId;
    private final int stateId;
    private final long sequence;
    private final Map<Integer, ItemStack> changedSlots;
    private final ItemStack carriedStack;

    public GuiClickTransaction(int containerId, int stateId, long sequence,
            Map<Integer, ItemStack> changedSlots, ItemStack carriedStack) {
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        if (stateId < 0) {
            throw new IllegalArgumentException("stateId must be non-negative");
        }
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
        Objects.requireNonNull(changedSlots, "changedSlots");
        this.containerId = containerId;
        this.stateId = stateId;
        this.sequence = sequence;
        Map<Integer, ItemStack> stable = new TreeMap<>();
        changedSlots.forEach((slot, stack) -> stable.put(
                Objects.requireNonNull(slot, "changed slot index"),
                Objects.requireNonNull(stack, "changed slot stack").copy()));
        this.changedSlots = Collections.unmodifiableMap(stable);
        this.carriedStack = Objects.requireNonNull(carriedStack, "carriedStack").copy();
    }

    public int containerId() {
        return containerId;
    }

    public int stateId() {
        return stateId;
    }

    public long sequence() {
        return sequence;
    }

    /** Returns a deep defensive copy because ItemStack itself is mutable. */
    public Map<Integer, ItemStack> changedSlots() {
        Map<Integer, ItemStack> copy = new TreeMap<>();
        changedSlots.forEach((slot, stack) -> copy.put(slot, stack.copy()));
        return Collections.unmodifiableMap(copy);
    }

    public ItemStack carriedStack() {
        return carriedStack.copy();
    }
}
