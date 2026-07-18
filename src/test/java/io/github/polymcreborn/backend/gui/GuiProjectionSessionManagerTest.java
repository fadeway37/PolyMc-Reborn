/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiProjectionSessionManagerTest {
    private static final Identifier ADAPTER = Identifier.parse("demo:menu");

    @Test
    void capacityNeverEvictsAndDisconnectCleansEveryPlayerSession() {
        var manager = new GuiProjectionSessionManager(2);
        UUID firstPlayer = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID secondPlayer = UUID.fromString("00000000-0000-0000-0000-000000000002");
        manager.open(firstPlayer, 1, ADAPTER);
        manager.open(firstPlayer, 2, ADAPTER);

        assertThrows(IllegalStateException.class, () -> manager.open(secondPlayer, 1, ADAPTER));
        assertEquals(2, manager.activeCount());
        assertEquals(2, manager.disconnect(firstPlayer));
        assertEquals(0, manager.activeCount());
    }

    @Test
    void generationPreventsStaleCloseAfterContainerIdReuse() {
        var manager = new GuiProjectionSessionManager(1);
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var old = manager.open(player, 4, ADAPTER);
        assertTrue(manager.close(old));
        var current = manager.open(player, 4, ADAPTER);

        assertFalse(manager.close(old));
        assertEquals(1, manager.activeCount());
        assertTrue(manager.close(current));
    }

    @Test
    void reconnectGetsANewGenerationAndOldConnectionCannotCloseIt() {
        var manager = new GuiProjectionSessionManager(2);
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var disconnected = manager.open(player, 7, ADAPTER);
        assertEquals(1, manager.disconnect(player));

        var reconnected = manager.open(player, 7, ADAPTER);

        assertTrue(reconnected.generation() > disconnected.generation());
        assertFalse(manager.close(disconnected));
        assertEquals(1, manager.activeCount());
        assertTrue(manager.close(reconnected));
    }

    @Test
    void duplicateOpenFailsWithoutReplacingTheActiveSession() {
        var manager = new GuiProjectionSessionManager(2);
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var active = manager.open(player, 3, ADAPTER);

        assertThrows(IllegalStateException.class, () -> manager.open(player, 3, ADAPTER));
        assertEquals(1, manager.activeCount());
        assertEquals(active, manager.snapshot().getFirst());
        assertTrue(manager.close(active));
    }
}
