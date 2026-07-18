/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer.entity;

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InteractionAuthorizationTest {
    @Test
    void acceptsOnlyACompleteCurrentTrackedSession() {
        assertEquals(InteractionAuthorization.Rejection.NONE,
                InteractionAuthorization.evaluate(valid()));
    }

    @Test
    void everySecurityBoundaryFailsClosed() {
        assertRejected(InteractionAuthorization.Rejection.SESSION_INACTIVE,
                check -> copy(check, false, check.sameSession(), check.expectedGeneration(),
                        check.currentGeneration(), check.sourceAlive(), check.playerAlive(), check.sameDimension(),
                        check.tracked(), check.distanceSquared(), check.maximumDistanceSquared()));
        assertRejected(InteractionAuthorization.Rejection.SESSION_REPLACED,
                check -> copy(check, check.sessionActive(), false, check.expectedGeneration(),
                        check.currentGeneration(), check.sourceAlive(), check.playerAlive(), check.sameDimension(),
                        check.tracked(), check.distanceSquared(), check.maximumDistanceSquared()));
        assertRejected(InteractionAuthorization.Rejection.GENERATION_MISMATCH,
                check -> copy(check, check.sessionActive(), check.sameSession(), 3L, 4L,
                        check.sourceAlive(), check.playerAlive(), check.sameDimension(), check.tracked(),
                        check.distanceSquared(), check.maximumDistanceSquared()));
        assertRejected(InteractionAuthorization.Rejection.SOURCE_NOT_ALIVE,
                check -> copy(check, check.sessionActive(), check.sameSession(), check.expectedGeneration(),
                        check.currentGeneration(), false, check.playerAlive(), check.sameDimension(), check.tracked(),
                        check.distanceSquared(), check.maximumDistanceSquared()));
        assertRejected(InteractionAuthorization.Rejection.PLAYER_NOT_ALIVE,
                check -> copy(check, check.sessionActive(), check.sameSession(), check.expectedGeneration(),
                        check.currentGeneration(), check.sourceAlive(), false, check.sameDimension(), check.tracked(),
                        check.distanceSquared(), check.maximumDistanceSquared()));
        assertRejected(InteractionAuthorization.Rejection.DIFFERENT_DIMENSION,
                check -> copy(check, check.sessionActive(), check.sameSession(), check.expectedGeneration(),
                        check.currentGeneration(), check.sourceAlive(), check.playerAlive(), false, check.tracked(),
                        check.distanceSquared(), check.maximumDistanceSquared()));
        assertRejected(InteractionAuthorization.Rejection.NOT_TRACKED,
                check -> copy(check, check.sessionActive(), check.sameSession(), check.expectedGeneration(),
                        check.currentGeneration(), check.sourceAlive(), check.playerAlive(), check.sameDimension(),
                        false, check.distanceSquared(), check.maximumDistanceSquared()));
        assertRejected(InteractionAuthorization.Rejection.OUT_OF_RANGE,
                check -> copy(check, check.sessionActive(), check.sameSession(), check.expectedGeneration(),
                        check.currentGeneration(), check.sourceAlive(), check.playerAlive(), check.sameDimension(),
                        check.tracked(), 37.0D, 36.0D));
        assertRejected(InteractionAuthorization.Rejection.OUT_OF_RANGE,
                check -> copy(check, check.sessionActive(), check.sameSession(), check.expectedGeneration(),
                        check.currentGeneration(), check.sourceAlive(), check.playerAlive(), check.sameDimension(),
                check.tracked(), Double.NaN, 36.0D));
    }

    @Test
    void exactDistanceBoundaryIsAcceptedButInvalidLimitsFailClosed() {
        var atBoundary = copy(valid(), true, true, 7L, 7L,
                true, true, true, true, 36.0D, 36.0D);
        assertEquals(InteractionAuthorization.Rejection.NONE,
                InteractionAuthorization.evaluate(atBoundary));

        for (double invalidMaximum : new double[] {
                0.0D, -1.0D, Double.NaN, Double.POSITIVE_INFINITY
        }) {
            var check = copy(valid(), true, true, 7L, 7L,
                    true, true, true, true, 0.0D, invalidMaximum);
            assertEquals(InteractionAuthorization.Rejection.OUT_OF_RANGE,
                    InteractionAuthorization.evaluate(check));
        }
    }

    @Test
    void replacedReconnectGenerationIsRejectedEvenWhenOtherFactsMatch() {
        var oldConnection = copy(valid(), true, false, 41L, 42L,
                true, true, true, true, 1.0D, 36.0D);

        assertEquals(InteractionAuthorization.Rejection.SESSION_REPLACED,
                InteractionAuthorization.evaluate(oldConnection));
    }

    private static InteractionAuthorization.Check valid() {
        return new InteractionAuthorization.Check(
                true, true, 7L, 7L, true, true, true, true, 25.0D, 36.0D);
    }

    private static void assertRejected(InteractionAuthorization.Rejection rejection,
                                       UnaryOperator<InteractionAuthorization.Check> mutation) {
        assertEquals(rejection, InteractionAuthorization.evaluate(mutation.apply(valid())));
    }

    private static InteractionAuthorization.Check copy(
            InteractionAuthorization.Check ignored,
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
        return new InteractionAuthorization.Check(sessionActive, sameSession, expectedGeneration,
                currentGeneration, sourceAlive, playerAlive, sameDimension, tracked, distanceSquared,
                maximumDistanceSquared);
    }
}
