/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import io.github.polymcreborn.api.gui.GuiClickTransaction;
import io.github.polymcreborn.api.gui.GuiInteractionPolicy;
import io.github.polymcreborn.api.gui.GuiProjection;
import io.github.polymcreborn.api.gui.GuiSlotMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Actual vanilla {@code GENERIC_9xN} menu backed directly by the real server container.
 * It adds the player's main inventory and hotbar using Minecraft's standard slot layout.
 */
public final class ProjectedContainerMenu extends AbstractContainerMenu {
    private final Container authoritativeContainer;
    private final GuiSlotMapping slotMapping;
    private final GuiInteractionPolicy interactionPolicy;
    private final int projectedSlotCount;
    private final GuiProjectionSessionManager sessions;
    private final GuiProjectionSessionManager.Session session;
    private final GuiTransactionValidator transactionValidator = new GuiTransactionValidator();
    private boolean closed;

    public ProjectedContainerMenu(int containerId, Inventory playerInventory, Identifier adapterId,
            GuiProjection projection, GuiProjectionSessionManager sessions) {
        super(menuType(Objects.requireNonNull(projection, "projection").rows()), containerId);
        Objects.requireNonNull(playerInventory, "playerInventory");
        Objects.requireNonNull(adapterId, "adapterId");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.authoritativeContainer = projection.authoritativeContainer();
        this.slotMapping = projection.slotMapping();
        this.interactionPolicy = projection.interactionPolicy();
        this.projectedSlotCount = projection.rows() * 9;

        for (int clientSlot = 0; clientSlot < projectedSlotCount; clientSlot++) {
            int column = clientSlot % 9;
            int row = clientSlot / 9;
            addSlot(new Slot(authoritativeContainer, slotMapping.serverSlotForClient(clientSlot),
                    8 + column * 18, 18 + row * 18));
        }
        addStandardInventorySlots(playerInventory, 8, 31 + projection.rows() * 18);

        this.session = sessions.open(playerInventory.player.getUUID(), containerId, adapterId);
        try {
            authoritativeContainer.startOpen(playerInventory.player);
        } catch (RuntimeException exception) {
            sessions.close(session);
            throw exception;
        }
    }

    public Container authoritativeContainer() {
        return authoritativeContainer;
    }

    public GuiSlotMapping slotMapping() {
        return slotMapping;
    }

    public GuiInteractionPolicy interactionPolicy() {
        return interactionPolicy;
    }

    public GuiProjectionSessionManager.Session session() {
        return session;
    }

    /**
     * Executes a validated click. Integrations should pass the packet's prediction claims here;
     * direct vanilla calls remain policy-gated but rely on Minecraft's built-in state checks.
     */
    public GuiTransactionResult transact(GuiClickTransaction transaction, int slotId, int button,
            ContainerInput input, Player player) {
        if (!isAllowedAction(slotId, button, input, player)) {
            broadcastFullState();
            return GuiTransactionResult.rejectAndResync("interaction denied by projection policy");
        }
        GuiServerSnapshot before = GuiServerSnapshot.capture(this);
        final GuiTransactionResult result;
        try {
            result = transactionValidator.validateAndApply(transaction, before,
                    () -> ProjectedContainerMenu.super.clicked(slotId, button, input, player),
                    () -> GuiServerSnapshot.capture(this));
        } catch (RuntimeException exception) {
            broadcastFullState();
            throw exception;
        }
        if (result.fullResyncRequired()) {
            broadcastFullState();
        }
        return result;
    }

    @Override
    public void clicked(int slotId, int button, ContainerInput input, Player player) {
        if (!isAllowedAction(slotId, button, input, player)) {
            broadcastFullState();
            return;
        }
        super.clicked(slotId, button, input, player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotId) {
        if (!interactionPolicy.quickMove() || slotId < 0 || slotId >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot slot = slots.get(slotId);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack source = slot.getItem();
        ItemStack original = source.copy();
        boolean moved = slotId < projectedSlotCount
                ? moveItemStackTo(source, projectedSlotCount, slots.size(), true)
                : moveItemStackTo(source, 0, projectedSlotCount, false);
        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (source.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (source.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, source);
        return original;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return interactionPolicy.quickCraftDrag() && super.canDragTo(slot);
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return interactionPolicy.pickupAll() && super.canTakeItemForPickAll(stack, slot);
    }

    @Override
    public boolean stillValid(Player player) {
        return !closed && authoritativeContainer.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        closeProjection(player);
    }

    private void closeProjection(Player player) {
        if (!closed) {
            closed = true;
            authoritativeContainer.stopOpen(player);
            sessions.close(session);
        }
    }

    private boolean isAllowedAction(int slotId, int button, ContainerInput input, Player player) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(player, "player");
        if (!isLegalSlotReference(slotId, input)) {
            return false;
        }
        return interactionPolicy.allows(input, slotId, button, player.isCreative());
    }

    private boolean isLegalSlotReference(int slotId, ContainerInput input) {
        if (slotId >= 0 && slotId < slots.size()) {
            return true;
        }
        return slotId == SLOT_CLICKED_OUTSIDE
                && (input == ContainerInput.PICKUP || input == ContainerInput.QUICK_CRAFT);
    }

    private static MenuType<?> menuType(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            case 6 -> MenuType.GENERIC_9x6;
            default -> throw new IllegalArgumentException(
                    "Projected standard container rows must be in [1, 6]");
        };
    }
}
