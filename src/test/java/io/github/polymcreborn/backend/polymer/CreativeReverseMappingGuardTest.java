/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativeReverseMappingGuardTest {
    @Test
    void acceptsOnlyAuthenticServerIssuedMarkers() {
        var guard = guard((byte) 7);
        var marker = guard.issue("demo:real_item", Map.of("count", "2", "custom_name", "Widget"));

        assertEquals("demo:real_item", guard.verify(marker).orElseThrow());
    }

    @Test
    void rejectsForgedTargetSignatureAndComponents() {
        var guard = guard((byte) 7);
        var marker = guard.issue("demo:real_item", Map.of("count", "1"));

        assertTrue(guard.verify(new CreativeReverseMappingGuard.Marker(1, "demo:admin_item",
                marker.components(), marker.signature())).isEmpty());
        assertTrue(guard.verify(new CreativeReverseMappingGuard.Marker(1, marker.targetRegistryId(),
                Map.of("count", "64"), marker.signature())).isEmpty());
        assertTrue(guard.verify(new CreativeReverseMappingGuard.Marker(1, marker.targetRegistryId(),
                marker.components(), "00".repeat(32))).isEmpty());
        assertTrue(guard.verify(new CreativeReverseMappingGuard.Marker(2, marker.targetRegistryId(),
                marker.components(), marker.signature())).isEmpty());
        assertTrue(guard.verify(null).isEmpty());
    }

    @Test
    void signaturesAreSecretSpecificAndCanonicalAcrossMapOrder() {
        var first = guard((byte) 1);
        var second = guard((byte) 2);
        var firstMarker = first.issue("demo:item", Map.of("count", "1", "lore", "safe"));
        var reordered = first.issue("demo:item", new java.util.LinkedHashMap<>(Map.of(
                "lore", "safe", "count", "1")));

        assertEquals(firstMarker.signature(), reordered.signature());
        assertTrue(second.verify(firstMarker).isEmpty());
    }

    @Test
    void refusesInvalidTargetsComponentsAndShortSecrets() {
        assertThrows(IllegalArgumentException.class, () -> new CreativeReverseMappingGuard(new byte[31]));
        var guard = guard((byte) 7);
        assertThrows(IllegalArgumentException.class, () -> guard.issue("not-namespaced", Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> guard.issue("demo:item", Map.of("operator_level", "4")));
        assertThrows(IllegalArgumentException.class,
                () -> guard.issue("demo:item", Map.of("lore", "x".repeat(4097))));
    }

    private static CreativeReverseMappingGuard guard(byte value) {
        var secret = new byte[32];
        java.util.Arrays.fill(secret, value);
        return new CreativeReverseMappingGuard(secret);
    }
}
