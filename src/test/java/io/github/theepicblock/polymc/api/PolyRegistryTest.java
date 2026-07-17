/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.theepicblock.polymc.api;

import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.legacy.LegacyCompatibilityProvider;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolyRegistryTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void legacyItemAndGlobalAdaptersBuildAnImmutablePolyMap() {
        var registry = new PolyRegistry();
        var item = testItem("legacy_item");
        var itemPoly = (io.github.theepicblock.polymc.api.item.ItemPoly)
                (input, player, location) -> input;
        registry.registerItemPoly(item, itemPoly);
        registry.registerGlobalItemPoly((original, input, map, player, location) -> input);

        var map = registry.build();

        assertTrue(registry.hasItemPoly(item));
        assertNotNull(map.getItemPoly(item));
        assertEquals(itemPoly, map.getItemPoly(item));
        assertEquals(1, registry.globalItemPolys().size());
        assertTrue(registry.hasGlobalItemPolys());
        var globalDecision = new LegacyCompatibilityProvider(registry).evaluate(
                ContentDescriptor.of("minecraft:dirt", "fixture", ContentType.ITEM, Map.of()),
                new MappingContext(null, false, true, Map.of())).orElseThrow();
        assertEquals(MappingStatus.LEGACY, globalDecision.status());
        assertEquals("legacy-global-item-adapter", globalDecision.strategy());
        assertTrue(map.isVanillaLikeMap());
        assertThrows(IllegalStateException.class,
                () -> registry.registerItemPoly(testItem("late_item"), (stack, player, location) -> stack));
    }

    @Test
    void unmappedLegacyItemsHaveNoAdapter() {
        var registry = new PolyRegistry();
        var item = testItem("unmapped_item");
        var map = registry.build();

        assertNull(map.getItemPoly(item));
    }

    private static Item testItem(String path) {
        return switch (path) {
            case "legacy_item" -> Items.STONE;
            case "unmapped_item" -> Items.DIRT;
            case "late_item" -> Items.DIAMOND;
            default -> throw new IllegalArgumentException(path);
        };
    }
}
