/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerData;

import java.util.Objects;
import java.util.Optional;

/**
 * A safe standard-container projection. The supplied {@link Container} remains the only mutable
 * inventory authority; a backend must not create a shadow inventory from it.
 */
public record GuiProjection(
        GuiProjectionKind kind,
        Container authoritativeContainer,
        int rows,
        GuiSlotMapping slotMapping,
        GuiInteractionPolicy interactionPolicy,
        Optional<ContainerData> propertyData) {

    public GuiProjection(Container authoritativeContainer, int rows,
                         GuiSlotMapping slotMapping, GuiInteractionPolicy interactionPolicy) {
        this(GuiProjectionKind.STANDARD_CONTAINER, authoritativeContainer, rows, slotMapping,
                interactionPolicy, Optional.empty());
    }

    public static GuiProjection furnace(Container authoritativeContainer,
                                         ContainerData propertyData,
                                         GuiInteractionPolicy interactionPolicy) {
        return new GuiProjection(GuiProjectionKind.FURNACE, authoritativeContainer, 0,
                GuiSlotMapping.identity(3), interactionPolicy, Optional.of(propertyData));
    }

    public GuiProjection {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(authoritativeContainer, "authoritativeContainer");
        Objects.requireNonNull(slotMapping, "slotMapping");
        Objects.requireNonNull(interactionPolicy, "interactionPolicy");
        propertyData = Objects.requireNonNull(propertyData, "propertyData");
        if (kind == GuiProjectionKind.STANDARD_CONTAINER) {
            if (rows < 1 || rows > 6) {
                throw new IllegalArgumentException("Projected standard container rows must be in [1, 6]");
            }
            if (slotMapping.projectedSlotCount() != rows * 9 || propertyData.isPresent()) {
                throw new IllegalArgumentException("A standard container projection has rows*9 slots and no properties");
            }
        } else if (rows != 0 || slotMapping.projectedSlotCount() != 3
                || propertyData.isEmpty() || propertyData.orElseThrow().getCount() < 4) {
            throw new IllegalArgumentException("A furnace projection requires three slots and four properties");
        }
        if (slotMapping.sourceContainerSize() != authoritativeContainer.getContainerSize()) {
            throw new IllegalArgumentException("Slot mapping source size "
                    + slotMapping.sourceContainerSize() + " does not match authoritative container size "
                    + authoritativeContainer.getContainerSize());
        }
    }
}
