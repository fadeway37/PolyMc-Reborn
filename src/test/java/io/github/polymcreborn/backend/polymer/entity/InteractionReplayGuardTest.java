/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InteractionReplayGuardTest {
    @Test
    void rejectsSameTickDuplicatesAndOlderReplaysPerPlayerAndAction() {
        var guard = new InteractionReplayGuard(4);
        var player = UUID.randomUUID();

        assertEquals(InteractionReplayGuard.Result.ACCEPTED,
                guard.accept(player, InteractionReplayGuard.Action.USE, 40));
        assertEquals(InteractionReplayGuard.Result.DUPLICATE_OR_REPLAYED,
                guard.accept(player, InteractionReplayGuard.Action.USE, 40));
        assertEquals(InteractionReplayGuard.Result.DUPLICATE_OR_REPLAYED,
                guard.accept(player, InteractionReplayGuard.Action.USE, 39));
        assertEquals(InteractionReplayGuard.Result.ACCEPTED,
                guard.accept(player, InteractionReplayGuard.Action.ATTACK, 40));
        assertEquals(InteractionReplayGuard.Result.ACCEPTED,
                guard.accept(player, InteractionReplayGuard.Action.USE, 41));
    }

    @Test
    void capacityIsBoundedAndClearRemovesOldConnectionState() {
        var guard = new InteractionReplayGuard(1);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();

        assertEquals(InteractionReplayGuard.Result.ACCEPTED,
                guard.accept(first, InteractionReplayGuard.Action.USE, 1));
        assertEquals(InteractionReplayGuard.Result.CAPACITY_EXHAUSTED,
                guard.accept(second, InteractionReplayGuard.Action.USE, 1));
        assertEquals(InteractionReplayGuard.Result.INVALID_TICK,
                guard.accept(first, InteractionReplayGuard.Action.ATTACK, -1));
        guard.clear();
        assertEquals(InteractionReplayGuard.Result.ACCEPTED,
                guard.accept(second, InteractionReplayGuard.Action.USE, 2));
    }
}
