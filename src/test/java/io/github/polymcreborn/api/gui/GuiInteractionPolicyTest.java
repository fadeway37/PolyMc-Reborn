/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiInteractionPolicyTest {
    @Test
    void standardPolicyExplicitlyCoversSensitiveInputs() {
        var policy = GuiInteractionPolicy.safeStandard();

        assertTrue(policy.allows(ContainerInput.QUICK_MOVE, 0, 0, false));
        assertTrue(policy.allows(ContainerInput.SWAP, 0, 8, false));
        assertTrue(policy.allows(ContainerInput.SWAP, 0, 40, false));
        assertTrue(policy.allows(ContainerInput.PICKUP,
                AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, 0, false));
        assertTrue(policy.allows(ContainerInput.THROW, 0, 1, false));
        assertTrue(policy.allows(ContainerInput.PICKUP_ALL, 0, 0, false));
        assertFalse(policy.allows(ContainerInput.SWAP, 0, 9, false));
        assertFalse(policy.allows(ContainerInput.CLONE, 0, 2, true));
    }

    @Test
    void quickCraftCloneRequiresBothCreativeModeAndExplicitPolicy() {
        int ordinary = AbstractContainerMenu.getQuickcraftMask(
                AbstractContainerMenu.QUICKCRAFT_HEADER_START,
                AbstractContainerMenu.QUICKCRAFT_TYPE_CHARITABLE);
        int clone = AbstractContainerMenu.getQuickcraftMask(
                AbstractContainerMenu.QUICKCRAFT_HEADER_START,
                AbstractContainerMenu.QUICKCRAFT_TYPE_CLONE);
        var safe = GuiInteractionPolicy.safeStandard();
        var cloneEnabled = new GuiInteractionPolicy(true, true, true, true,
                true, true, true, true, true);

        assertTrue(safe.allows(ContainerInput.QUICK_CRAFT,
                AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, ordinary, false));
        assertFalse(safe.allows(ContainerInput.QUICK_CRAFT,
                AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, clone, true));
        assertFalse(cloneEnabled.allows(ContainerInput.QUICK_CRAFT,
                AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, clone, false));
        assertTrue(cloneEnabled.allows(ContainerInput.QUICK_CRAFT,
                AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, clone, true));
    }
}
