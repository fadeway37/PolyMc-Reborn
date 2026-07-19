/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded O(1) per-player pack state used by packet-time projections. */
public final class PlayerPackStateService {
    public enum State {
        UNKNOWN,
        OFFERED,
        ACCEPTED,
        DOWNLOADED,
        APPLIED,
        DECLINED,
        REQUIRED_REJECTED,
        FAILED,
        DISABLED
    }

    private final ResourcePackPolicy policy;
    private final int capacity;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final AtomicLong rejectedCapacity = new AtomicLong();
    private final AtomicLong offeredCount = new AtomicLong();
    private final AtomicLong appliedCount = new AtomicLong();
    private final AtomicLong declinedCount = new AtomicLong();
    private final AtomicLong requiredRejectedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();

    public PlayerPackStateService(ResourcePackPolicy policy, int capacity) {
        this.policy = java.util.Objects.requireNonNull(policy, "policy");
        if (capacity < 1 || capacity > 1_000_000) {
            throw new IllegalArgumentException("capacity must be in [1, 1000000]");
        }
        this.capacity = capacity;
    }

    public ResourcePackPolicy policy() {
        return policy;
    }

    public void offered(UUID playerId) {
        offeredCount.incrementAndGet();
        update(playerId, policy == ResourcePackPolicy.DISABLED ? State.DISABLED : State.OFFERED);
    }

    public State response(UUID playerId, ServerboundResourcePackPacket.Action action) {
        State state = switch (action) {
            case ACCEPTED -> State.ACCEPTED;
            case DOWNLOADED -> State.DOWNLOADED;
            case SUCCESSFULLY_LOADED -> State.APPLIED;
            case DECLINED -> policy == ResourcePackPolicy.REQUIRED
                    ? State.REQUIRED_REJECTED : State.DECLINED;
            case FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> State.FAILED;
        };
        State previous = state(playerId);
        if (previous == state) {
            return state;
        }
        if (state == State.APPLIED) {
            appliedCount.incrementAndGet();
        } else if (state == State.DECLINED || state == State.REQUIRED_REJECTED) {
            declinedCount.incrementAndGet();
            if (state == State.REQUIRED_REJECTED) {
                requiredRejectedCount.incrementAndGet();
            }
        } else if (state == State.FAILED) {
            failedCount.incrementAndGet();
        }
        update(playerId, state);
        return state;
    }

    public State disconnected(UUID playerId) {
        State previous = states.remove(playerId);
        if (policy == ResourcePackPolicy.REQUIRED && previous == State.OFFERED) {
            declinedCount.incrementAndGet();
            requiredRejectedCount.incrementAndGet();
            return State.REQUIRED_REJECTED;
        }
        return previous == null ? State.UNKNOWN : previous;
    }

    public State state(UUID playerId) {
        return policy == ResourcePackPolicy.DISABLED
                ? State.DISABLED : states.getOrDefault(playerId, State.UNKNOWN);
    }

    public boolean customResourcesAllowed(PacketContext context) {
        if (policy == ResourcePackPolicy.DISABLED) {
            return false;
        }
        // A null context is an internal/server-side projection query, not a
        // player whose pack state is unknown. Keeping it deterministic also
        // lets planning and GameTest inspect the frozen allocation itself.
        if (context == null) {
            return true;
        }
        var profile = context.get(PacketContext.GAME_PROFILE);
        if (profile == null) {
            return policy == ResourcePackPolicy.REQUIRED;
        }
        State state = state(profile.id());
        return state == State.APPLIED || policy == ResourcePackPolicy.REQUIRED
                && state != State.DECLINED && state != State.REQUIRED_REJECTED
                && state != State.FAILED;
    }

    public Map<UUID, State> snapshot() {
        return Map.copyOf(states);
    }

    public long rejectedCapacityCount() {
        return rejectedCapacity.get();
    }

    public Stats stats() {
        return new Stats(states.size(), offeredCount.get(), appliedCount.get(), declinedCount.get(),
                requiredRejectedCount.get(), failedCount.get(), rejectedCapacity.get());
    }

    public record Stats(int activePlayers, long offered, long applied, long declined,
                        long requiredRejected, long failed, long capacityRejected) {
    }

    private void update(UUID playerId, State state) {
        java.util.Objects.requireNonNull(playerId, "playerId");
        if (!states.containsKey(playerId) && states.size() >= capacity) {
            rejectedCapacity.incrementAndGet();
            return;
        }
        states.put(playerId, state);
    }
}
