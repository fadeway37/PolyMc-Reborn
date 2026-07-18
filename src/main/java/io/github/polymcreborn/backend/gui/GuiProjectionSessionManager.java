/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded, generation-safe tracking for open projected menus. */
public final class GuiProjectionSessionManager {
    private final int maximumSessions;
    private final Map<Key, Session> sessions = new HashMap<>();
    private long nextGeneration = 1;

    public GuiProjectionSessionManager(int maximumSessions) {
        if (maximumSessions < 1 || maximumSessions > 65_536) {
            throw new IllegalArgumentException("maximumSessions must be in [1, 65536]");
        }
        this.maximumSessions = maximumSessions;
    }

    public synchronized Session open(UUID playerId, int containerId, Identifier adapterId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(adapterId, "adapterId");
        if (containerId < 0) {
            throw new IllegalArgumentException("containerId must be non-negative");
        }
        Key key = new Key(playerId, containerId);
        if (sessions.containsKey(key)) {
            throw new IllegalStateException("A projected GUI session already exists for player "
                    + playerId + " and container " + containerId);
        }
        if (sessions.size() >= maximumSessions) {
            throw new IllegalStateException("Projected GUI session capacity exhausted ("
                    + maximumSessions + ")");
        }
        if (nextGeneration == Long.MAX_VALUE) {
            throw new IllegalStateException("Projected GUI session generation exhausted");
        }
        Session session = new Session(playerId, containerId, adapterId, nextGeneration++);
        sessions.put(key, session);
        return session;
    }

    /** A stale menu cannot close a newer session that reused its container id. */
    public synchronized boolean close(Session session) {
        Objects.requireNonNull(session, "session");
        Key key = new Key(session.playerId(), session.containerId());
        Session active = sessions.get(key);
        if (active == null || active.generation() != session.generation()) {
            return false;
        }
        sessions.remove(key);
        return true;
    }

    /** Connection-close hook: removes every session owned by the disconnected player. */
    public synchronized int disconnect(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        int before = sessions.size();
        sessions.keySet().removeIf(key -> key.playerId().equals(playerId));
        return before - sessions.size();
    }

    public synchronized int activeCount() {
        return sessions.size();
    }

    public synchronized List<Session> snapshot() {
        List<Session> result = new ArrayList<>(sessions.values());
        result.sort(Comparator.comparing((Session session) -> session.playerId().toString())
                .thenComparingInt(Session::containerId)
                .thenComparingLong(Session::generation));
        return List.copyOf(result);
    }

    private record Key(UUID playerId, int containerId) {
    }

    public record Session(UUID playerId, int containerId, Identifier adapterId, long generation) {
        public Session {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(adapterId, "adapterId");
        }
    }
}
