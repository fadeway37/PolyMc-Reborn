/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Explicit server-side semantics for interaction with a projected entity.
 * Implementations are invoked only after the backend's session, tracking,
 * dimension, liveness, and distance checks have all passed.
 */
public interface EntityProjectionInteraction<T extends Entity> {
    EntityProjectionInteraction<Entity> DENY_ALL = new EntityProjectionInteraction<>() {
    };

    default void use(T target, ServerPlayer player, InteractionHand hand, Vec3 hitPosition,
                     boolean secondaryAction) {
    }

    default void attack(T target, ServerPlayer player) {
    }

    @SuppressWarnings("unchecked")
    static <T extends Entity> EntityProjectionInteraction<T> denyAll() {
        return (EntityProjectionInteraction<T>) DENY_ALL;
    }
}
