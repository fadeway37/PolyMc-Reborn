/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.PolyMcRebornEntrypoint;
import io.github.polymcreborn.api.ProviderTier;
import io.github.polymcreborn.api.ResourceContributor;
import io.github.polymcreborn.api.entity.EntityProjectionRegistry;
import io.github.polymcreborn.api.gui.GuiProjectionRegistry;
import io.github.polymcreborn.compat.CompatibilityRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Loads modern adapters in stable provider/definition order and fixes their tier to EXPLICIT. */
public final class ExtensionEntrypointLoader {
    private final CompatibilityRegistry compatibilityRegistry;
    private final EntityProjectionRegistry entityProjections;
    private final GuiProjectionRegistry guiProjections;
    private final List<OwnedResourceContributor> resources = new ArrayList<>();

    public ExtensionEntrypointLoader(CompatibilityRegistry compatibilityRegistry,
                                     EntityProjectionRegistry entityProjections,
                                     GuiProjectionRegistry guiProjections) {
        this.compatibilityRegistry = compatibilityRegistry;
        this.entityProjections = entityProjections;
        this.guiProjections = guiProjections;
    }

    public void load() {
        var containers = FabricLoader.getInstance()
                .getEntrypointContainers("polymc-reborn", PolyMcRebornEntrypoint.class).stream()
                .sorted(Comparator.comparing((EntrypointContainer<PolyMcRebornEntrypoint> container) ->
                                container.getProvider().getMetadata().getId())
                        .thenComparing(container -> String.valueOf(container.getDefinition())))
                .toList();
        for (var container : containers) {
            var providerMod = container.getProvider().getMetadata().getId();
            var entrypoint = container.getEntrypoint();
            entrypoint.registerCompatibility(provider -> compatibilityRegistry.register(explicit(providerMod, provider)));
            entrypoint.registerResources((ownerMod, contributor) ->
                    resources.add(new OwnedResourceContributor(ownerMod, contributor)));
            entrypoint.registerEntityProjections(entityProjections);
            entrypoint.registerGuiProjections(guiProjections);
        }
        resources.sort(Comparator.comparing(OwnedResourceContributor::ownerMod)
                .thenComparing(resource -> resource.contributor().getClass().getName()));
    }

    public List<OwnedResourceContributor> resources() {
        return List.copyOf(resources);
    }

    private static CompatibilityProvider explicit(String providerMod, CompatibilityProvider delegate) {
        return new CompatibilityProvider() {
            @Override
            public String id() {
                return "adapter:" + providerMod + ":" + delegate.id();
            }

            @Override
            public ProviderTier tier() {
                return ProviderTier.EXPLICIT;
            }

            @Override
            public java.util.Optional<MappingDecision> evaluate(
                    io.github.polymcreborn.api.ContentDescriptor descriptor,
                    io.github.polymcreborn.api.MappingContext context) {
                return delegate.evaluate(descriptor, context)
                        .map(decision -> decision.withProvider(id(), MappingStatus.EXPLICIT));
            }
        };
    }

    public record OwnedResourceContributor(String ownerMod, ResourceContributor contributor) {
    }
}
