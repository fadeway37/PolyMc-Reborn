/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer.entity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, per-player same-tick replay guard for explicit entity callbacks. */
final class InteractionReplayGuard {
    private final int maximumEntries;
    private final Map<Key, Long> acceptedTicks = new LinkedHashMap<>();

    InteractionReplayGuard(int maximumEntries) {
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    synchronized Result accept(UUID playerId, Action action, long gameTick) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(action, "action");
        if (gameTick < 0) {
            return Result.INVALID_TICK;
        }
        var key = new Key(playerId, action);
        Long previous = acceptedTicks.get(key);
        if (previous != null && gameTick <= previous) {
            return Result.DUPLICATE_OR_REPLAYED;
        }
        if (previous == null && acceptedTicks.size() >= maximumEntries) {
            return Result.CAPACITY_EXHAUSTED;
        }
        acceptedTicks.put(key, gameTick);
        return Result.ACCEPTED;
    }

    synchronized void clear() {
        acceptedTicks.clear();
    }

    enum Action {
        USE,
        ATTACK
    }

    enum Result {
        ACCEPTED,
        DUPLICATE_OR_REPLAYED,
        CAPACITY_EXHAUSTED,
        INVALID_TICK
    }

    private record Key(UUID playerId, Action action) {
    }
}
