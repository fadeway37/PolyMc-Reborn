/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.legacy;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.ProviderTier;
import io.github.theepicblock.polymc.api.PolyRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;

/** Exposes explicit legacy registrations to the new explainable planner. */
public final class LegacyCompatibilityProvider implements CompatibilityProvider {
    private final PolyRegistry registry;

    public LegacyCompatibilityProvider(PolyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String id() {
        return "legacy-polymc-adapter";
    }

    @Override
    public ProviderTier tier() {
        return ProviderTier.LEGACY;
    }

    @Override
    public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        var id = Identifier.parse(descriptor.registryId());
        boolean registered = switch (descriptor.contentType()) {
            case ITEM -> registry.hasItemPoly(BuiltInRegistries.ITEM.getValue(id))
                    || registry.hasGlobalItemPolys();
            case BLOCK -> registry.hasBlockPoly(BuiltInRegistries.BLOCK.getValue(id));
            case ENTITY -> registry.hasEntityPoly(BuiltInRegistries.ENTITY_TYPE.getValue(id));
            case GUI -> registry.hasGuiPoly(BuiltInRegistries.MENU.getValue(id));
        };
        if (!registered) {
            return Optional.empty();
        }
        String strategy = switch (descriptor.contentType()) {
            case ITEM -> registry.hasItemPoly(BuiltInRegistries.ITEM.getValue(id))
                    ? "legacy-item-adapter" : "legacy-global-item-adapter";
            case BLOCK -> "legacy-block-adapter";
            case ENTITY -> "legacy-entity-classified";
            case GUI -> "legacy-gui-classified";
        };
        String carrier = descriptor.contentType() == io.github.polymcreborn.api.ContentType.ITEM
                ? "minecraft:paper" : descriptor.contentType() == io.github.polymcreborn.api.ContentType.BLOCK
                ? "minecraft:barrier" : "";
        if (descriptor.contentType() == io.github.polymcreborn.api.ContentType.ENTITY
                || descriptor.contentType() == io.github.polymcreborn.api.ContentType.GUI) {
            return Optional.of(new MappingDecision(descriptor, MappingStatus.UNSUPPORTED, id(),
                    "classification-only", strategy, "", 1, 100,
                    List.of("A recompiled extension registered this object through the legacy polymc entrypoint",
                            "The registration is retained for diagnostics, but no safe generic backend exists"),
                    List.of(), List.of("The legacy registration has no runtime projection in 0.1"),
                    "PolyMc Reborn 0.1 does not execute generic legacy "
                            + descriptor.contentType().name().toLowerCase() + " adapters"));
        }
        var warnings = registry.hasGlobalItemPolys()
                && descriptor.contentType() == io.github.polymcreborn.api.ContentType.ITEM
                ? List.of("A legacy global item transformer takes precedence over automatic item inference")
                : List.<String>of();
        return Optional.of(new MappingDecision(descriptor, MappingStatus.LEGACY, id(), "legacy-polymer",
                strategy, carrier, 0.9, warnings.isEmpty() ? 15 : 100,
                List.of("A recompiled extension registered this object through the legacy polymc entrypoint",
                        "The legacy registration was adapted into the immutable Reborn plan"),
                List.of(), warnings, null));
    }
}
