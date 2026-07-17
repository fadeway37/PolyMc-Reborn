/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.ProviderTier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Mutable only during initialization; freezes before planning begins. */
public final class CompatibilityRegistry {
    private final List<CompatibilityProvider> providers = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();
    private boolean frozen;

    public synchronized void register(CompatibilityProvider provider) {
        Objects.requireNonNull(provider, "provider");
        if (frozen) {
            throw new IllegalStateException("Compatibility registry is frozen");
        }
        if (!ids.add(provider.id())) {
            throw new IllegalArgumentException("Duplicate compatibility provider id: " + provider.id());
        }
        providers.add(provider);
    }

    public synchronized List<CompatibilityProvider> freezeAndOrder(MappingContext context) {
        frozen = true;
        return providers.stream()
                .sorted(Comparator.<CompatibilityProvider>comparingInt(
                                provider -> effectiveRank(provider.tier(), context))
                        .thenComparing(CompatibilityProvider::id))
                .toList();
    }

    public synchronized boolean isFrozen() {
        return frozen;
    }

    private static int effectiveRank(ProviderTier tier, MappingContext context) {
        if (context.overrideNativePolymer()) {
            if (tier == ProviderTier.ADMIN_FORCE) {
                return 0;
            }
            if (tier == ProviderTier.NATIVE) {
                return 1;
            }
            return tier.ordinal() + 1;
        }
        return tier.ordinal();
    }
}
