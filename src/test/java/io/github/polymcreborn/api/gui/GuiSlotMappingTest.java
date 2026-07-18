/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.world.SimpleContainer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GuiSlotMappingTest {
    @Test
    void buildsStrictBijectionAndDefendsItsArrays() {
        int[] slots = {2, 0, 3};
        var mapping = GuiSlotMapping.fromClientToServer(4, slots);
        slots[0] = 1;

        assertEquals(2, mapping.serverSlotForClient(0));
        assertEquals(1, mapping.clientSlotForServer(0).orElseThrow());
        assertFalse(mapping.clientSlotForServer(1).isPresent());
        int[] exported = mapping.clientToServer();
        exported[0] = 1;
        assertArrayEquals(new int[]{2, 0, 3}, mapping.clientToServer());
    }

    @Test
    void rejectsDuplicatesAndEveryOutOfBoundsAccess() {
        assertThrows(IllegalArgumentException.class,
                () -> GuiSlotMapping.fromClientToServer(3, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> GuiSlotMapping.fromClientToServer(3, 0, 3));
        var mapping = GuiSlotMapping.identity(9);
        assertThrows(IndexOutOfBoundsException.class, () -> mapping.serverSlotForClient(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> mapping.serverSlotForClient(9));
        assertThrows(IndexOutOfBoundsException.class, () -> mapping.clientSlotForServer(9));
    }

    @Test
    void projectionAcceptsOnlyOneToSixCompleteVanillaRows() {
        var container = new SimpleContainer(9);
        var valid = new GuiProjection(container, 1, GuiSlotMapping.identity(9),
                GuiInteractionPolicy.safeStandard());
        assertEquals(container, valid.authoritativeContainer());

        assertThrows(IllegalArgumentException.class, () -> new GuiProjection(container, 0,
                GuiSlotMapping.identity(9), GuiInteractionPolicy.safeStandard()));
        assertThrows(IllegalArgumentException.class, () -> new GuiProjection(container, 1,
                GuiSlotMapping.fromClientToServer(9, 0, 1), GuiInteractionPolicy.safeStandard()));
        assertThrows(IllegalArgumentException.class, () -> new GuiProjection(container, 1,
                GuiSlotMapping.identity(8), GuiInteractionPolicy.safeStandard()));
    }
}
