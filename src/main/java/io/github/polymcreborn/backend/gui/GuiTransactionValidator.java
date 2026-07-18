/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import io.github.polymcreborn.api.gui.GuiClickTransaction;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * Stateful replay guard and prediction reconciler. Client stacks are observations only: the
 * mutation is executed against the real server container before predictions are compared.
 */
public final class GuiTransactionValidator {
    private long lastConsumedSequence = -1;

    public synchronized GuiTransactionResult validateAndApply(
            GuiClickTransaction transaction,
            GuiServerSnapshot before,
            Runnable authoritativeMutation,
            Supplier<GuiServerSnapshot> afterSnapshot) {
        if (transaction.containerId() != before.containerId()) {
            return GuiTransactionResult.rejectAndResync("container id mismatch");
        }
        if (transaction.stateId() != before.stateId()) {
            return GuiTransactionResult.rejectAndResync("stale container state id");
        }
        if (transaction.sequence() <= lastConsumedSequence) {
            return GuiTransactionResult.rejectAndResync("duplicate or replayed transaction sequence");
        }
        Map<Integer, ItemStack> claims = transaction.changedSlots();
        for (var claim : claims.entrySet()) {
            if (claim.getKey() < 0 || claim.getKey() >= before.slotCount()) {
                return GuiTransactionResult.rejectAndResync("changed slot index outside menu bounds");
            }
            if (!isSafeClaim(claim.getValue())) {
                return GuiTransactionResult.rejectAndResync("changed slot contains an invalid stack");
            }
        }
        if (!isSafeClaim(transaction.carriedStack())) {
            return GuiTransactionResult.rejectAndResync("carried claim contains an invalid stack");
        }

        lastConsumedSequence = transaction.sequence();
        authoritativeMutation.run();
        GuiServerSnapshot after = afterSnapshot.get();
        if (after.containerId() != before.containerId() || after.slotCount() != before.slotCount()) {
            return GuiTransactionResult.rejectAndResync("server menu identity changed during transaction");
        }

        Map<Integer, ItemStack> actualChanges = changedSlots(before, after);
        if (!actualChanges.keySet().equals(claims.keySet())) {
            return GuiTransactionResult.rejectAndResync("client changed-slot set did not match server result");
        }
        for (var actual : actualChanges.entrySet()) {
            if (!ItemStack.matches(actual.getValue(), claims.get(actual.getKey()))) {
                return GuiTransactionResult.rejectAndResync("client changed-slot stack did not match server result");
            }
        }
        if (!ItemStack.matches(after.carried(), transaction.carriedStack())) {
            return GuiTransactionResult.rejectAndResync("client carried stack did not match server result");
        }
        return GuiTransactionResult.accepted();
    }

    public synchronized long lastConsumedSequence() {
        return lastConsumedSequence;
    }

    private static Map<Integer, ItemStack> changedSlots(
            GuiServerSnapshot before, GuiServerSnapshot after) {
        Map<Integer, ItemStack> changed = new TreeMap<>();
        for (int slot = 0; slot < before.slotCount(); slot++) {
            ItemStack afterStack = after.slot(slot);
            if (!ItemStack.matches(before.slot(slot), afterStack)) {
                changed.put(slot, afterStack);
            }
        }
        return changed;
    }

    private static boolean isSafeClaim(ItemStack stack) {
        if (stack.isEmpty()) {
            return stack.getCount() == 0;
        }
        return stack.getCount() > 0 && stack.getCount() <= stack.getMaxStackSize();
    }
}
