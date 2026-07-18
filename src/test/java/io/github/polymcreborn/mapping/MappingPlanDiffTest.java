/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import io.github.polymcreborn.api.ContentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingPlanDiffTest {
    @Test
    void reportsStableAssignmentResourceInvalidationAndCapacityChanges() {
        var before = document(
                mapping("demo:alpha", "lit=false", "polymer:block/1", "a".repeat(64)),
                mapping("demo:removed", "", "polymer:block/2", ""),
                mapping("demo:changed", "", "polymer:block/3", ""));
        var after = document(
                mapping("demo:added", "", "polymer:block/4", ""),
                mapping("demo:alpha", "lit=false", "polymer:block/1", "b".repeat(64)),
                mapping("demo:changed", "", "polymer:block/9", ""));

        var diff = MappingPlanDiff.compare(before, after, Set.of("block:demo:changed"),
                Map.of("textured-full-cube", 2));

        assertEquals(List.of("block:demo:added", "block:demo:alpha[lit=false]",
                        "block:demo:changed", "block:demo:removed"),
                diff.entries().stream().map(MappingPlanDiff.Entry::key).toList());
        assertEquals(1, diff.counts().get(MappingPlanDiff.ChangeKind.ADDED));
        assertEquals(1, diff.counts().get(MappingPlanDiff.ChangeKind.REMOVED));
        assertEquals(1, diff.counts().get(MappingPlanDiff.ChangeKind.PRESERVED));
        assertEquals(1, diff.counts().get(MappingPlanDiff.ChangeKind.REASSIGNED));
        assertEquals(1, diff.counts().get(MappingPlanDiff.ChangeKind.RESOURCE_CHANGED));
        assertEquals(1, diff.counts().get(MappingPlanDiff.ChangeKind.INVALIDATED));
        assertEquals(3, diff.counts().get(MappingPlanDiff.ChangeKind.CAPACITY_RISK));
        assertTrue(diff.hasIncompatibleChanges());
    }

    private static MappingStoreDocument document(StoredMapping... mappings) {
        return new MappingStoreDocument(MappingStoreDocument.SCHEMA_VERSION,
                MappingStoreDocument.ALGORITHM_VERSION, List.of(mappings));
    }

    private static StoredMapping mapping(String id, String state, String carrier, String hash) {
        return new StoredMapping(id, ContentType.BLOCK, state, "textured-full-cube", carrier, hash,
                "0.1", "0.2");
    }
}
