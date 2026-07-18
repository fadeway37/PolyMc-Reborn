/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.mapping.MappingStoreDocument;
import io.github.polymcreborn.mapping.PersistentMappingStore;
import io.github.polymcreborn.mapping.StoredMapping;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BlockStateMappingMigrationTest {
    @Test
    void replacesLegacyWholeBlockKeyWithoutChangingItsCarrier(@TempDir Path directory) {
        var legacy = mapping("", "minecraft:note_block[note=1]", "0.1.0-alpha.1+26.1.2");
        var old = new MappingStoreDocument(MappingStoreDocument.SCHEMA_VERSION,
                MappingStoreDocument.ALGORITHM_VERSION, List.of(legacy));
        var explicitDefault = mapping("active=false", legacy.clientCarrier(), legacy.createdWith());
        var explicitActive = mapping("active=true", "minecraft:note_block[note=2]",
                "0.2.0-alpha.1+26.1.2");

        var mergeBase = PolymerCompatibilityBackend.withoutRetiredMappings(old, Set.of(legacy.key()));
        var merged = new PersistentMappingStore(directory)
                .mergePreservingAssignments(mergeBase, List.of(explicitDefault, explicitActive));

        assertEquals(List.of("active=false", "active=true"),
                merged.mappings().stream().map(StoredMapping::state).toList());
        assertEquals(legacy.clientCarrier(), merged.mappings().getFirst().clientCarrier());
        assertEquals(legacy.createdWith(), merged.mappings().getFirst().createdWith());
    }

    @Test
    void stateAssignmentsRemainByteDeterministicWhenDiscoveredInDifferentOrders(@TempDir Path directory) {
        var off = mapping("active=false", "minecraft:note_block[note=1]", "0.2.0-alpha.1+26.1.2");
        var on = mapping("active=true", "minecraft:note_block[note=2]", "0.2.0-alpha.1+26.1.2");
        var store = new PersistentMappingStore(directory);

        var forward = store.mergePreservingAssignments(MappingStoreDocument.empty(), List.of(off, on));
        var reverse = store.mergePreservingAssignments(MappingStoreDocument.empty(), List.of(on, off));

        assertArrayEquals(store.serialize(forward), store.serialize(reverse));
        assertEquals(List.of("active=false", "active=true"),
                reverse.mappings().stream().map(StoredMapping::state).toList());
    }

    private static StoredMapping mapping(String state, String carrier, String createdWith) {
        return new StoredMapping("demo:stateful", ContentType.BLOCK, state, "textured-full-cube", carrier,
                "", createdWith, "0.2.0-alpha.1+26.1.2");
    }
}
