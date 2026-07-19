/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.entity;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** One validated explicit entity adapter and its canonical registry identifiers. */
public record EntityProjectionRegistration(
        Identifier adapterId,
        Identifier targetTypeId,
        Identifier surrogateTypeId,
        EntityType<? extends Entity> targetType,
        EntityType<?> surrogateType,
        double maxInteractionDistance,
        Vec3 offset,
        EntityProjectionComposition composition,
        EntityProjectionInteraction<? extends Entity> interaction,
        EntityProjectionAdapter<? extends Entity> adapter)
        implements Comparable<EntityProjectionRegistration> {

    public EntityProjectionRegistration {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(targetTypeId, "targetTypeId");
        Objects.requireNonNull(surrogateTypeId, "surrogateTypeId");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(surrogateType, "surrogateType");
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(composition, "composition");
        Objects.requireNonNull(interaction, "interaction");
        Objects.requireNonNull(adapter, "adapter");
    }

    @Override
    public int compareTo(EntityProjectionRegistration other) {
        int byTarget = targetTypeId.compareTo(other.targetTypeId);
        return byTarget != 0 ? byTarget : adapterId.compareTo(other.adapterId);
    }
}
