/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.ProviderTier;

import java.util.List;
import java.util.Optional;

/** Explicit terminal classification used instead of unsafe guesses. */
public final class UnsupportedProvider implements CompatibilityProvider {
    @Override
    public String id() {
        return "unsupported";
    }

    @Override
    public ProviderTier tier() {
        return ProviderTier.UNSUPPORTED;
    }

    @Override
    public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        String reason = switch (descriptor.contentType()) {
            case BLOCK -> {
                if (descriptor.booleanAttribute("shape_analysis_failed")) {
                    yield "Automatic block shape analysis failed safely ("
                            + descriptor.attributes().getOrDefault("shape_analysis_error", "unknown failure") + ")";
                }
                yield descriptor.booleanAttribute("has_block_entity")
                        ? "Automatic MVP mapping refuses blocks with block entities"
                        : "Automatic MVP mapping supports only stable full-cube blocks";
            }
            case ENTITY -> "Generic entity projection is outside the 0.1 safety boundary";
            case GUI -> "Generic GUI projection is outside the 0.1 transaction-safety boundary";
            case ITEM -> "No safe item projection was produced";
        };
        return Optional.of(new MappingDecision(descriptor, MappingStatus.UNSUPPORTED, id(), "none",
                "unsupported", "", 1, 100, List.of(reason), List.of(), List.of(), reason));
    }
}
