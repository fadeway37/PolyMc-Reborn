/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.entity;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityProjectionRegistryTest {
    @BeforeAll
    static void bootstrapRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void freezeProducesStableTargetIdOrderAndIdentityLookups() {
        var registry = new EntityProjectionRegistry();
        var zombie = adapter("test:zombie", EntityType.ZOMBIE, EntityType.ARMOR_STAND);
        var allay = adapter("test:allay", EntityType.ALLAY, EntityType.BAT);
        registry.register(zombie);
        registry.register(allay);

        var snapshot = registry.freeze();

        assertEquals(List.of("minecraft:allay", "minecraft:zombie"), snapshot.entries().stream()
                .map(entry -> entry.targetTypeId().toString()).toList());
        assertSame(allay, snapshot.find(EntityType.ALLAY).orElseThrow().adapter());
        assertSame(snapshot, registry.freeze(), "freeze is intentionally idempotent");
    }

    @Test
    void duplicateAdapterIdAndTargetAreRejected() {
        var registry = new EntityProjectionRegistry();
        registry.register(adapter("test:one", EntityType.PIG, EntityType.ARMOR_STAND));

        var duplicateId = assertThrows(IllegalArgumentException.class,
                () -> registry.register(adapter("test:one", EntityType.COW, EntityType.ARMOR_STAND)));
        assertTrue(duplicateId.getMessage().contains("duplicate entity projection adapter id"));

        var duplicateTarget = assertThrows(IllegalArgumentException.class,
                () -> registry.register(adapter("test:two", EntityType.PIG, EntityType.BAT)));
        assertTrue(duplicateTarget.getMessage().contains("duplicate entity projection target type"));
    }

    @Test
    void lateRegistrationAndEarlySnapshotAreRejected() {
        var registry = new EntityProjectionRegistry();
        assertThrows(IllegalStateException.class, registry::snapshot);
        registry.freeze();

        var exception = assertThrows(IllegalStateException.class,
                () -> registry.register(adapter("test:late", EntityType.PIG, EntityType.ARMOR_STAND)));
        assertTrue(exception.getMessage().contains("frozen"));
    }

    @Test
    void unsafeDistanceIsRejected() {
        var registry = new EntityProjectionRegistry();
        var unsafe = new EntityProjectionAdapter<Entity>() {
            @Override
            public String id() {
                return "test:unsafe";
            }

            @Override
            public EntityType<Entity> targetType() {
                @SuppressWarnings("unchecked")
                EntityType<Entity> type = (EntityType<Entity>) (EntityType<?>) EntityType.PIG;
                return type;
            }

            @Override
            public EntityType<?> surrogateType() {
                return EntityType.ARMOR_STAND;
            }

            @Override
            public double maxInteractionDistance() {
                return Double.POSITIVE_INFINITY;
            }
        };

        assertThrows(IllegalArgumentException.class, () -> registry.register(unsafe));
    }

    private static <T extends Entity> EntityProjectionAdapter<T> adapter(
            String id, EntityType<T> target, EntityType<?> surrogate) {
        return EntityProjectionAdapter.of(id, target, surrogate, EntityProjectionInteraction.denyAll());
    }
}
