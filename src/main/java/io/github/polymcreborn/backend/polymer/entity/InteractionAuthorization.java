/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer.entity;

/** Pure fail-closed authorization decision used by projected entity interactions. */
public final class InteractionAuthorization {
    private InteractionAuthorization() {
    }

    public static Rejection evaluate(Check check) {
        if (!check.sessionActive()) {
            return Rejection.SESSION_INACTIVE;
        }
        if (!check.sameSession()) {
            return Rejection.SESSION_REPLACED;
        }
        if (check.expectedGeneration() != check.currentGeneration()) {
            return Rejection.GENERATION_MISMATCH;
        }
        if (!check.sourceAlive()) {
            return Rejection.SOURCE_NOT_ALIVE;
        }
        if (!check.playerAlive()) {
            return Rejection.PLAYER_NOT_ALIVE;
        }
        if (!check.sameDimension()) {
            return Rejection.DIFFERENT_DIMENSION;
        }
        if (!check.tracked()) {
            return Rejection.NOT_TRACKED;
        }
        if (!Double.isFinite(check.distanceSquared()) || !Double.isFinite(check.maximumDistanceSquared())
                || check.maximumDistanceSquared() <= 0.0D
                || check.distanceSquared() > check.maximumDistanceSquared()) {
            return Rejection.OUT_OF_RANGE;
        }
        return Rejection.NONE;
    }

    public record Check(
            boolean sessionActive,
            boolean sameSession,
            long expectedGeneration,
            long currentGeneration,
            boolean sourceAlive,
            boolean playerAlive,
            boolean sameDimension,
            boolean tracked,
            double distanceSquared,
            double maximumDistanceSquared) {
    }

    public enum Rejection {
        NONE,
        SESSION_INACTIVE,
        SESSION_REPLACED,
        GENERATION_MISMATCH,
        SOURCE_NOT_ALIVE,
        PLAYER_NOT_ALIVE,
        DIFFERENT_DIMENSION,
        NOT_TRACKED,
        OUT_OF_RANGE
    }
}
