/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.MappingStatus;

/** One provider's explainable participation in a decision. */
public record CandidateTrace(String provider, boolean matched, MappingStatus proposedStatus, String summary) {
    public CandidateTrace {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        summary = summary == null ? "" : summary;
    }
}
