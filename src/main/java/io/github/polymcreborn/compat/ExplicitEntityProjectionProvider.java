/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.ProviderTier;
import io.github.polymcreborn.api.entity.EntityProjectionRegistry;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/** Planner view of initialization-time explicit entity projection adapters. */
public final class ExplicitEntityProjectionProvider implements CompatibilityProvider {
    private final EntityProjectionRegistry.Snapshot registrations;

    public ExplicitEntityProjectionProvider(EntityProjectionRegistry.Snapshot registrations) {
        this.registrations = registrations;
    }

    @Override
    public String id() {
        return "explicit-entity-projections";
    }

    @Override
    public ProviderTier tier() {
        return ProviderTier.EXPLICIT;
    }

    @Override
    public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        if (descriptor.contentType() != ContentType.ENTITY) {
            return Optional.empty();
        }
        Identifier target = Identifier.tryParse(descriptor.registryId());
        if (target == null) {
            return Optional.empty();
        }
        return registrations.find(target).map(registration -> new MappingDecision(
                descriptor,
                MappingStatus.EXPLICIT,
                id(),
                "polymer-virtual-entity",
                "explicit-vanilla-surrogate",
                registration.surrogateTypeId().toString(),
                1.0D,
                20,
                List.of("A Java adapter explicitly registered this real entity type",
                        "The real server entity remains authoritative while a vanilla surrogate is attached"),
                List.of(),
                List.of("Only adapter-declared metadata and interactions are projected"),
                ""));
    }
}
