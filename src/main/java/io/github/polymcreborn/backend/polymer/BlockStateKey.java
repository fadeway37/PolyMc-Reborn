/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Comparator;

/** Canonical, locale-independent keys for server states and vanilla carrier states. */
public final class BlockStateKey {
    private BlockStateKey() {
    }

    /**
     * Returns the stable value stored in {@code StoredMapping.state}. An empty key is deliberately retained for
     * property-free blocks so mappings written by the 0.1 algorithm remain valid.
     */
    public static String canonicalProperties(BlockState state) {
        return state.getProperties().stream()
                .sorted(Comparator.comparing(Property::getName))
                .map(property -> property.getName() + "=" + propertyValue(state, property))
                .collect(java.util.stream.Collectors.joining(","));
    }

    /** Returns a canonical namespaced vanilla state such as {@code minecraft:note_block[note=1,...]}. */
    public static String canonicalCarrier(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String properties = canonicalProperties(state);
        return properties.isEmpty() ? id : id + "[" + properties + "]";
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
