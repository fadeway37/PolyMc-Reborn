/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeuristicProviderTest {
    private final HeuristicProvider provider = new HeuristicProvider();

    @Test
    void selectsConservativeSemanticItemCarriersDeterministically() {
        assertCarrier("food", "minecraft:apple");
        assertCarrier("drink", "minecraft:honey_bottle");
        assertCarrier("tool", "minecraft:iron_pickaxe");
        assertCarrier("armor", "minecraft:iron_chestplate");
        assertCarrier("bow", "minecraft:bow");
        assertCarrier("crossbow", "minecraft:crossbow");
        assertCarrier("shield", "minecraft:shield");
        assertCarrier("throwable", "minecraft:snowball");
        assertCarrier("block_item", "minecraft:stone");
        assertCarrier("material", "minecraft:paper");
    }

    @Test
    void mapsSimpleAndMultiStateFullCubeBlocks() {
        var simple = block(Map.of("full_cube", "true", "stable_shape", "true",
                "has_block_entity", "false", "state_count", "1"));
        var states = block(Map.of("full_cube", "true", "stable_shape", "true",
                "has_block_entity", "false", "state_count", "4"));

        var simpleDecision = provider.evaluate(simple, MappingContext.vanillaSafe()).orElseThrow();
        var stateDecision = provider.evaluate(states, MappingContext.vanillaSafe()).orElseThrow();

        assertEquals("textured-full-cube", simpleDecision.strategy());
        assertTrue(simpleDecision.warnings().isEmpty());
        assertEquals(MappingStatus.HEURISTIC, stateDecision.status());
        assertTrue(stateDecision.warnings().isEmpty());
        assertTrue(stateDecision.resourceDependencies().contains("assets/demo/blockstates/cube.json"));
        assertTrue(stateDecision.reasonChain().stream().anyMatch(reason -> reason.contains("Every safe server state")));
    }

    @Test
    void refusesUnsafeBlockShapesAndBlockEntities() {
        var nonFull = block(Map.of("full_cube", "false", "stable_shape", "true",
                "has_block_entity", "false"));
        var dynamic = block(Map.of("full_cube", "true", "stable_shape", "false",
                "has_block_entity", "false"));
        var entity = block(Map.of("full_cube", "true", "stable_shape", "true",
                "has_block_entity", "true"));
        var unsafeBreaking = block(Map.of("full_cube", "true", "stable_shape", "true",
                "has_block_entity", "false", "breaking_semantics_safe", "false"));

        assertTrue(provider.evaluate(nonFull, MappingContext.vanillaSafe()).isEmpty());
        assertTrue(provider.evaluate(dynamic, MappingContext.vanillaSafe()).isEmpty());
        assertTrue(provider.evaluate(entity, MappingContext.vanillaSafe()).isEmpty());
        assertTrue(provider.evaluate(unsafeBreaking, MappingContext.vanillaSafe()).isEmpty());
    }

    @Test
    void unknownItemCategoryFallsThroughInsteadOfGuessing() {
        var descriptor = ContentDescriptor.of("demo:odd", "demo", ContentType.ITEM,
                Map.of("item_category", "custom_renderer_only"));

        assertTrue(provider.evaluate(descriptor, MappingContext.vanillaSafe()).isEmpty());
    }

    private void assertCarrier(String category, String expected) {
        var descriptor = ContentDescriptor.of("demo:" + category, "demo", ContentType.ITEM,
                Map.of("item_category", category));
        var first = provider.evaluate(descriptor, MappingContext.vanillaSafe()).orElseThrow();
        var second = provider.evaluate(descriptor, MappingContext.vanillaSafe()).orElseThrow();
        assertEquals(expected, first.clientCarrier());
        assertEquals(first, second);
    }

    private static ContentDescriptor block(Map<String, String> attributes) {
        return ContentDescriptor.of("demo:cube", "demo", ContentType.BLOCK, attributes);
    }
}
