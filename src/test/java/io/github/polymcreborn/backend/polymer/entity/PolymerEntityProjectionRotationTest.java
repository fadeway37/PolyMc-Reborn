/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PolymerEntityProjectionRotationTest {
    @Test
    void mapsMinecraftPitchAndYawToPolymerInApiOrder() {
        var received = new float[2];

        PolymerEntityProjectionBackend.synchronizeRotation((pitch, yaw) -> {
            received[0] = pitch;
            received[1] = yaw;
        }, -17.5F, 123.25F);

        assertEquals(-17.5F, received[0]);
        assertEquals(123.25F, received[1]);
    }
}
