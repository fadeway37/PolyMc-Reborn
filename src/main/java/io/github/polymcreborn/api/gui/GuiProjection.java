/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.world.Container;

import java.util.Objects;

/**
 * A safe standard-container projection. The supplied {@link Container} remains the only mutable
 * inventory authority; a backend must not create a shadow inventory from it.
 */
public record GuiProjection(
        Container authoritativeContainer,
        int rows,
        GuiSlotMapping slotMapping,
        GuiInteractionPolicy interactionPolicy) {

    public GuiProjection {
        Objects.requireNonNull(authoritativeContainer, "authoritativeContainer");
        Objects.requireNonNull(slotMapping, "slotMapping");
        Objects.requireNonNull(interactionPolicy, "interactionPolicy");
        if (rows < 1 || rows > 6) {
            throw new IllegalArgumentException("Projected standard container rows must be in [1, 6]");
        }
        if (slotMapping.projectedSlotCount() != rows * 9) {
            throw new IllegalArgumentException("A " + rows + " row projection must map exactly "
                    + (rows * 9) + " client slots");
        }
        if (slotMapping.sourceContainerSize() != authoritativeContainer.getContainerSize()) {
            throw new IllegalArgumentException("Slot mapping source size "
                    + slotMapping.sourceContainerSize() + " does not match authoritative container size "
                    + authoritativeContainer.getContainerSize());
        }
    }
}
