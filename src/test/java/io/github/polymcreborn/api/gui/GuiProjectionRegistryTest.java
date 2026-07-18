/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.resources.Identifier;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiProjectionRegistryTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void freezeSortsByStableMenuIdAndPublishesIdentityLookup() {
        var registry = new GuiProjectionRegistry();
        var six = adapter("demo:six", MenuType.GENERIC_9x6);
        var one = adapter("demo:one", MenuType.GENERIC_9x1);
        registry.register(six);
        registry.register(one);

        var snapshot = registry.freeze();

        assertEquals(List.of("minecraft:generic_9x1", "minecraft:generic_9x6"),
                snapshot.entries().stream().map(entry -> entry.menuTypeId().toString()).toList());
        assertSame(one, snapshot.find(MenuType.GENERIC_9x1).orElseThrow());
        assertTrue(registry.isFrozen());
        assertSame(snapshot, registry.freeze());
    }

    @Test
    void rejectsDuplicateIdsDuplicateTargetsAndLateRegistration() {
        var duplicateId = new GuiProjectionRegistry();
        duplicateId.register(adapter("demo:same", MenuType.GENERIC_9x1));
        assertThrows(IllegalArgumentException.class,
                () -> duplicateId.register(adapter("demo:same", MenuType.GENERIC_9x2)));

        var duplicateTarget = new GuiProjectionRegistry();
        duplicateTarget.register(adapter("demo:first", MenuType.GENERIC_9x1));
        assertThrows(IllegalArgumentException.class,
                () -> duplicateTarget.register(adapter("demo:second", MenuType.GENERIC_9x1)));

        duplicateTarget.freeze();
        assertThrows(IllegalStateException.class,
                () -> duplicateTarget.register(adapter("demo:late", MenuType.GENERIC_9x2)));
    }

    private static GuiProjectionAdapter adapter(String id, MenuType<?> type) {
        return new GuiProjectionAdapter() {
            @Override
            public Identifier id() {
                return Identifier.parse(id);
            }

            @Override
            public MenuType<?> serverMenuType() {
                return type;
            }

            @Override
            public GuiProjection project(AbstractContainerMenu sourceMenu, ServerPlayer player) {
                return null;
            }
        };
    }
}
