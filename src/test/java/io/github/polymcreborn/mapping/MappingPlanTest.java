/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MappingPlanTest {
    @Test
    void providesStableIterationAndConstantKeyLookupSnapshot() {
        var zeta = descriptor("zeta:item", ContentType.ITEM);
        var alpha = descriptor("alpha:block", ContentType.BLOCK);
        var plan = new MappingPlan(List.of(decision(zeta), decision(alpha)), Map.of());

        assertEquals(List.of(alpha, zeta), plan.orderedDecisions().stream()
                .map(MappingDecision::descriptor).toList());
        assertEquals(decision(alpha), plan.decision(alpha));
        assertEquals(decision(zeta), plan.decision(ContentType.ITEM, "zeta:item"));
        assertNull(plan.decision(ContentType.GUI, "missing:menu"));
        assertEquals(1, plan.decisionsForRegistryId("alpha:block").size());
    }

    @Test
    void rejectsDuplicateDescriptorKeys() {
        var descriptor = descriptor("demo:item", ContentType.ITEM);

        assertThrows(IllegalArgumentException.class,
                () -> new MappingPlan(List.of(decision(descriptor), decision(descriptor)), Map.of()));
    }

    private static ContentDescriptor descriptor(String id, ContentType type) {
        return ContentDescriptor.of(id, id.substring(0, id.indexOf(':')), type, Map.of());
    }

    private static MappingDecision decision(ContentDescriptor descriptor) {
        return new MappingDecision(descriptor, MappingStatus.EXPLICIT, "test", "polymer", "test",
                "minecraft:paper", 1, 0, List.of("test"), List.of(), List.of(), null);
    }
}
