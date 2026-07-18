/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import io.github.polymcreborn.api.gui.GuiClickTransaction;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiTransactionValidatorTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void acceptsOnlyPredictionThatExactlyMatchesAuthoritativeMutation() {
        var validator = new GuiTransactionValidator();
        var before = snapshot(4, 7, ItemStack.EMPTY, stack(Items.STONE));
        var after = snapshot(4, 7, stack(Items.DIRT), stack(Items.STONE));
        var state = new AtomicReference<>(before);
        var transaction = new GuiClickTransaction(4, 7, 1,
                Map.of(0, stack(Items.DIRT)), ItemStack.EMPTY);

        var result = validator.validateAndApply(transaction, before,
                () -> state.set(after), state::get);

        assertTrue(result.acceptedTransaction());
        assertFalse(result.fullResyncRequired());
        assertEquals(1, validator.lastConsumedSequence());
    }

    @Test
    void staleDuplicateAndOutOfBoundsTransactionsFailBeforeMutation() {
        var validator = new GuiTransactionValidator();
        var before = snapshot(3, 9, ItemStack.EMPTY);
        var mutated = new AtomicBoolean();

        var stale = validator.validateAndApply(
                new GuiClickTransaction(3, 8, 1, Map.of(), ItemStack.EMPTY), before,
                () -> mutated.set(true), () -> before);
        assertTrue(stale.fullResyncRequired());
        assertFalse(mutated.get());

        var outOfBounds = validator.validateAndApply(
                new GuiClickTransaction(3, 9, 1, Map.of(1, ItemStack.EMPTY), ItemStack.EMPTY), before,
                () -> mutated.set(true), () -> before);
        assertTrue(outOfBounds.fullResyncRequired());
        assertFalse(mutated.get());

        var accepted = validator.validateAndApply(
                new GuiClickTransaction(3, 9, 1, Map.of(), ItemStack.EMPTY), before,
                () -> { }, () -> before);
        assertTrue(accepted.acceptedTransaction());
        var duplicate = validator.validateAndApply(
                new GuiClickTransaction(3, 9, 1, Map.of(), ItemStack.EMPTY), before,
                () -> mutated.set(true), () -> before);
        assertTrue(duplicate.fullResyncRequired());
        assertFalse(mutated.get());
    }

    @Test
    void forgedChangedSlotsAndCarriedStackRequireFullResync() {
        var before = snapshot(8, 2, ItemStack.EMPTY);
        var after = snapshot(8, 2, stack(Items.STONE));

        var missingChange = new GuiTransactionValidator().validateAndApply(
                new GuiClickTransaction(8, 2, 1, Map.of(), ItemStack.EMPTY), before,
                () -> { }, () -> after);
        assertTrue(missingChange.fullResyncRequired());

        var wrongStack = new GuiTransactionValidator().validateAndApply(
                new GuiClickTransaction(8, 2, 1,
                        Map.of(0, stack(Items.DIRT)), ItemStack.EMPTY), before,
                () -> { }, () -> after);
        assertTrue(wrongStack.fullResyncRequired());

        var forgedCarried = new GuiTransactionValidator().validateAndApply(
                new GuiClickTransaction(8, 2, 1,
                        Map.of(0, stack(Items.STONE)), stack(Items.DIAMOND)), before,
                () -> { }, () -> after);
        assertTrue(forgedCarried.fullResyncRequired());
    }

    @Test
    void invalidStackCountsFailBeforeAuthoritativeMutation() {
        var before = snapshot(5, 1, ItemStack.EMPTY);
        var oversized = stack(Items.STONE);
        oversized.setCount(oversized.getMaxStackSize() + 1);
        var mutated = new AtomicBoolean();

        var oversizedResult = new GuiTransactionValidator().validateAndApply(
                new GuiClickTransaction(5, 1, 1, Map.of(0, oversized), ItemStack.EMPTY), before,
                () -> mutated.set(true), () -> before);
        var oversizedCarriedResult = new GuiTransactionValidator().validateAndApply(
                new GuiClickTransaction(5, 1, 1, Map.of(), oversized), before,
                () -> mutated.set(true), () -> before);

        assertTrue(oversizedResult.fullResyncRequired());
        assertTrue(oversizedCarriedResult.fullResyncRequired());
        assertFalse(mutated.get());
    }

    @Test
    void failedPredictionAfterServerMutationStillConsumesSequence() {
        var validator = new GuiTransactionValidator();
        var before = snapshot(6, 4, ItemStack.EMPTY);
        var after = snapshot(6, 4, stack(Items.STONE));
        var mutationCount = new java.util.concurrent.atomic.AtomicInteger();
        var transaction = new GuiClickTransaction(6, 4, 12, Map.of(), ItemStack.EMPTY);

        var mismatch = validator.validateAndApply(transaction, before,
                mutationCount::incrementAndGet, () -> after);
        var replay = validator.validateAndApply(transaction, before,
                mutationCount::incrementAndGet, () -> before);

        assertTrue(mismatch.fullResyncRequired());
        assertTrue(replay.fullResyncRequired());
        assertEquals(1, mutationCount.get(), "a mismatched prediction must not make its sequence replayable");
        assertEquals(12, validator.lastConsumedSequence());
    }

    private static GuiServerSnapshot snapshot(int containerId, int stateId, ItemStack... stacks) {
        return new GuiServerSnapshot(containerId, stateId, List.of(stacks), ItemStack.EMPTY);
    }

    private static ItemStack stack(Item item) {
        return new ItemStack(Holder.direct(item, DataComponentMap.EMPTY), 1);
    }
}
