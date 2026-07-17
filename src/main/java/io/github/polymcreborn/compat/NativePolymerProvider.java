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

/** Recognizes objects already projected by Polymer and protects them from silent replacement. */
public final class NativePolymerProvider implements CompatibilityProvider {
    @Override
    public String id() {
        return "native-polymer";
    }

    @Override
    public ProviderTier tier() {
        return ProviderTier.NATIVE;
    }

    @Override
    public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        if (!descriptor.booleanAttribute("native_polymer")) {
            return Optional.empty();
        }
        return Optional.of(new MappingDecision(descriptor, MappingStatus.NATIVE, id(), "polymer",
                "preserve-native", descriptor.registryId(), 1, 0,
                List.of("The registered object already exposes a native Polymer projection",
                        "Native mappings are protected unless override_native_polymer is explicitly enabled"),
                List.of(), List.of(), null));
    }
}
