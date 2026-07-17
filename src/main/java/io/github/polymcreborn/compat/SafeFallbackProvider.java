/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.ProviderTier;

import java.util.List;
import java.util.Optional;

/** Last usable projection for items only; geometry is never guessed for unsupported blocks. */
public final class SafeFallbackProvider implements CompatibilityProvider {
    @Override
    public String id() {
        return "safe-fallback";
    }

    @Override
    public ProviderTier tier() {
        return ProviderTier.FALLBACK;
    }

    @Override
    public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        if (descriptor.contentType() != ContentType.ITEM) {
            return Optional.empty();
        }
        return Optional.of(new MappingDecision(descriptor, MappingStatus.FALLBACK, id(), "polymer",
                "generic-material", "minecraft:paper", 0.35, 60,
                List.of("No reliable semantic item carrier was identified",
                        "A generic material projection is safer than leaking the custom registry entry"),
                List.of(), List.of("Special client-side behavior and rendering are not preserved"), null));
    }
}
