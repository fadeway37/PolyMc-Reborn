/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContentDescriptorTest {
    @Test
    void canonicalizesAttributesAndStateKeys() {
        var attributes = new LinkedHashMap<String, String>();
        attributes.put("zeta", "last");
        attributes.put("state", "axis=y");
        attributes.put("alpha", "first");

        var descriptor = ContentDescriptor.of("demo:block", "demo", ContentType.BLOCK, attributes);

        assertEquals(List.of("alpha", "state", "zeta"), List.copyOf(descriptor.attributes().keySet()));
        assertEquals("block:demo:block[axis=y]", descriptor.key());
        assertThrows(UnsupportedOperationException.class,
                () -> descriptor.attributes().put("late", "mutation"));
    }

    @Test
    void rejectsMalformedIdentifiersAndInvalidDecisions() {
        assertThrows(IllegalArgumentException.class,
                () -> ContentDescriptor.of("not_namespaced", "demo", ContentType.ITEM, Map.of()));
        var descriptor = ContentDescriptor.of("demo:item", "demo", ContentType.ITEM, Map.of());
        assertThrows(IllegalArgumentException.class, () -> new MappingDecision(descriptor,
                MappingStatus.UNSUPPORTED, "test", "none", "unsupported", "", 1, 100,
                List.of(), List.of(), List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new MappingDecision(descriptor,
                MappingStatus.EXPLICIT, "test", "polymer", "mapping", "minecraft:paper", 1.1, 0,
                List.of(), List.of(), List.of(), null));
    }
}
