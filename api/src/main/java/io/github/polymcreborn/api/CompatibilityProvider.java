/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

import java.util.Optional;

/** Proposes a compatibility decision without mutating registries or applying a backend. */
public interface CompatibilityProvider {
    String id();

    ProviderTier tier();

    Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context);

    /** Stable explanation used by reports and the why command when no candidate is produced. */
    default String explainNotApplicable(ContentDescriptor descriptor, MappingContext context) {
        return "not applicable";
    }
}
