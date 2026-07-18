/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import java.util.Objects;

/** Result of validating one predicted click against the authoritative server result. */
public record GuiTransactionResult(Outcome outcome, String reason) {
    public GuiTransactionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
    }

    public static GuiTransactionResult accepted() {
        return new GuiTransactionResult(Outcome.ACCEPTED, "client prediction matched server state");
    }

    public static GuiTransactionResult rejectAndResync(String reason) {
        return new GuiTransactionResult(Outcome.REJECTED_FULL_RESYNC, reason);
    }

    public boolean acceptedTransaction() {
        return outcome == Outcome.ACCEPTED;
    }

    public boolean fullResyncRequired() {
        return outcome == Outcome.REJECTED_FULL_RESYNC;
    }

    public enum Outcome {
        ACCEPTED,
        REJECTED_FULL_RESYNC
    }
}
