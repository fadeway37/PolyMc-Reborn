/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolymerRegistrySanitizerTest {
    @Test
    void recognizesOnlyNamespacesUnderstoodByAnUnmodifiedClient() {
        assertTrue(PolymerRegistrySanitizer.isVanillaNamespace(Identifier.parse("minecraft:stone")));
        assertTrue(PolymerRegistrySanitizer.isVanillaNamespace(Identifier.parse("brigadier:string")));
        assertFalse(PolymerRegistrySanitizer.isVanillaNamespace(Identifier.parse("example:stone")));
    }

    @Test
    void sanitizationEvidenceIsSortedAndImmutable() {
        var result = new PolymerRegistrySanitizer.RegistrySanitization(Map.of(
                Identifier.parse("minecraft:item"), List.of(
                        Identifier.parse("example:z"), Identifier.parse("example:a")),
                Identifier.parse("minecraft:block"), List.of(Identifier.parse("example:b"))));

        assertEquals(List.of(Identifier.parse("minecraft:block"), Identifier.parse("minecraft:item")),
                List.copyOf(result.entriesByRegistry().keySet()));
        assertEquals(List.of(Identifier.parse("example:a"), Identifier.parse("example:z")),
                result.entriesByRegistry().get(Identifier.parse("minecraft:item")));
        assertEquals(3, result.totalEntries());
        assertThrows(UnsupportedOperationException.class, () -> result.entriesByRegistry().clear());
    }
}
