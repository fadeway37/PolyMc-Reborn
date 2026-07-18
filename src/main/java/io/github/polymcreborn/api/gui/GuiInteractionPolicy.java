/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

import java.util.Objects;

/**
 * Explicit allow-list for interactions accepted by a projected vanilla container.
 * Creative cloning is deliberately separate from ordinary creative-mode use.
 */
public record GuiInteractionPolicy(
        boolean pickup,
        boolean outsidePickup,
        boolean quickMove,
        boolean quickCraftDrag,
        boolean hotbarSwap,
        boolean offhandSwap,
        boolean creativeClone,
        boolean throwAction,
        boolean pickupAll) {

    /**
     * Safe standard chest behavior. Creative clone is excluded because it can manufacture items.
     */
    public static GuiInteractionPolicy safeStandard() {
        return new GuiInteractionPolicy(true, true, true, true, true, true,
                false, true, true);
    }

    /** A projection that only permits closing and observation. */
    public static GuiInteractionPolicy readOnly() {
        return new GuiInteractionPolicy(false, false, false, false, false, false,
                false, false, false);
    }

    /**
     * Checks both the input kind and its encoded button. Slot bounds are checked by the menu.
     */
    public boolean allows(ContainerInput input, int slotId, int button, boolean creativePlayer) {
        Objects.requireNonNull(input, "input");
        return switch (input) {
            case PICKUP -> pickup && (slotId != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE
                    || outsidePickup) && (button == 0 || button == 1);
            case QUICK_MOVE -> quickMove && (button == 0 || button == 1);
            case SWAP -> (button >= 0 && button < 9 && hotbarSwap)
                    || (button == 40 && offhandSwap);
            case CLONE -> creativePlayer && creativeClone && button == 2;
            case THROW -> throwAction && (button == 0 || button == 1);
            case QUICK_CRAFT -> allowsQuickCraft(button, creativePlayer);
            case PICKUP_ALL -> pickupAll && button == 0;
        };
    }

    private boolean allowsQuickCraft(int button, boolean creativePlayer) {
        if (!quickCraftDrag) {
            return false;
        }
        int header = AbstractContainerMenu.getQuickcraftHeader(button);
        int type = AbstractContainerMenu.getQuickcraftType(button);
        if (header < AbstractContainerMenu.QUICKCRAFT_HEADER_START
                || header > AbstractContainerMenu.QUICKCRAFT_HEADER_END) {
            return false;
        }
        return type == AbstractContainerMenu.QUICKCRAFT_TYPE_CHARITABLE
                || type == AbstractContainerMenu.QUICKCRAFT_TYPE_GREEDY
                || (type == AbstractContainerMenu.QUICKCRAFT_TYPE_CLONE
                && creativePlayer && creativeClone);
    }
}
