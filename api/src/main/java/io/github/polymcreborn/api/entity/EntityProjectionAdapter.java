/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Backend-neutral, explicit projection from one real server entity type to a vanilla client type. */
public interface EntityProjectionAdapter<T extends Entity> {
    /** Stable, namespaced adapter identifier used in reports and deterministic ordering. */
    String id();

    /** The real registered server entity type. */
    EntityType<T> targetType();

    /** A registered vanilla entity type used only as the client representation. */
    EntityType<?> surrogateType();

    /** Maximum accepted use/attack distance; deliberately conservative by default. */
    default double maxInteractionDistance() {
        return 6.0D;
    }

    /** Optional visual offset relative to the real entity anchor. */
    default Vec3 offset() {
        return Vec3.ZERO;
    }

    /** Explicit interaction semantics. The default rejects both use and attack. */
    default EntityProjectionInteraction<T> interaction() {
        return EntityProjectionInteraction.denyAll();
    }

    /** Optional passenger/equipment composition, explicit and empty by default. */
    default EntityProjectionComposition composition() {
        return EntityProjectionComposition.none();
    }

    static <T extends Entity> EntityProjectionAdapter<T> of(
            String id, EntityType<T> targetType, EntityType<?> surrogateType,
            EntityProjectionInteraction<T> interaction) {
        Objects.requireNonNull(interaction, "interaction");
        return new EntityProjectionAdapter<>() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public EntityType<T> targetType() {
                return targetType;
            }

            @Override
            public EntityType<?> surrogateType() {
                return surrogateType;
            }

            @Override
            public EntityProjectionInteraction<T> interaction() {
                return interaction;
            }
        };
    }
}
