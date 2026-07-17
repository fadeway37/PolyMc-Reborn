/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.ProviderTier;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Initialization-time adapter registrations exposed as one deterministic provider. */
public final class RegisteredDecisionProvider implements CompatibilityProvider {
    private final String id;
    private final ProviderTier tier;
    private final Map<String, MappingDecision> decisions = new HashMap<>();
    private boolean frozen;

    public RegisteredDecisionProvider(String id, ProviderTier tier) {
        this.id = Objects.requireNonNull(id, "id");
        this.tier = Objects.requireNonNull(tier, "tier");
    }

    public synchronized void register(MappingDecision decision) {
        if (frozen) {
            throw new IllegalStateException("Adapter provider is frozen: " + id);
        }
        var previous = decisions.putIfAbsent(decision.descriptor().key(), decision);
        if (previous != null) {
            throw new IllegalArgumentException("Duplicate adapter for " + decision.descriptor().key());
        }
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public ProviderTier tier() {
        return tier;
    }

    @Override
    public synchronized Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        frozen = true;
        var decision = decisions.get(descriptor.key());
        return Optional.ofNullable(decision == null ? null : decision.withProvider(id, decision.status()));
    }
}
