/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.ProviderTier;
import io.github.polymcreborn.api.gui.GuiProjectionRegistry;

import java.util.List;
import java.util.Optional;

/** Planner view of explicit standard-container adapters; unknown menus remain unsupported. */
public final class ExplicitGuiProjectionProvider implements CompatibilityProvider {
    private final GuiProjectionRegistry.Snapshot registrations;

    public ExplicitGuiProjectionProvider(GuiProjectionRegistry.Snapshot registrations) {
        this.registrations = registrations;
    }

    @Override
    public String id() {
        return "explicit-gui-projections";
    }

    @Override
    public ProviderTier tier() {
        return ProviderTier.EXPLICIT;
    }

    @Override
    public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        if (descriptor.contentType() != ContentType.GUI) {
            return Optional.empty();
        }
        return registrations.entries().stream()
                .filter(entry -> entry.menuTypeId().toString().equals(descriptor.registryId()))
                .findFirst()
                .map(entry -> new MappingDecision(
                        descriptor,
                        MappingStatus.EXPLICIT,
                        id(),
                        "vanilla-standard-container",
                        "explicit-standard-9xn-container",
                        "minecraft:generic_9xn",
                        1.0D,
                        15,
                        List.of("A Java adapter explicitly registered this server MenuType",
                                "The projection uses one bounded vanilla 9xN menu backed by the real Container"),
                        List.of(),
                        List.of("Only adapters satisfying the strict slot and interaction policy are executable"),
                        ""));
    }
}
