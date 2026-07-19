/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Explicit optional visual composition for a projected entity. */
public record EntityProjectionComposition(
        Optional<EntityType<?>> passengerType,
        Vec3 passengerOffset,
        List<Equipment> equipment) {

    public EntityProjectionComposition {
        passengerType = Objects.requireNonNull(passengerType, "passengerType");
        passengerOffset = Objects.requireNonNull(passengerOffset, "passengerOffset");
        equipment = List.copyOf(equipment);
    }

    public static EntityProjectionComposition none() {
        return new EntityProjectionComposition(Optional.empty(), Vec3.ZERO, List.of());
    }

    /**
     * A single vanilla visual item. The backend creates its stack only after
     * Minecraft has bound data components, so adapters may register safely
     * during Fabric initialization.
     */
    public record Equipment(EquipmentSlot slot, Item item) {
        public Equipment {
            Objects.requireNonNull(slot, "slot");
            Objects.requireNonNull(item, "item");
        }
    }
}
