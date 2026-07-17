/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.ProviderTier;
import io.github.polymcreborn.config.CompatibilityProfile;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Converts matching declarative data into a proposal; profiles can never load code. */
public final class DeclarativeProfileProvider implements CompatibilityProvider {
    private static final Map<String, String> ITEM_CARRIERS = Map.of(
            "food", "minecraft:apple", "drink", "minecraft:honey_bottle",
            "tool", "minecraft:iron_pickaxe", "armor", "minecraft:iron_chestplate",
            "bow", "minecraft:bow", "crossbow", "minecraft:crossbow",
            "shield", "minecraft:shield", "throwable", "minecraft:snowball",
            "block_item", "minecraft:stone", "material", "minecraft:paper");

    private final List<CompatibilityProfile> profiles;
    private final boolean forced;

    public DeclarativeProfileProvider(List<CompatibilityProfile> profiles, boolean forced) {
        this.profiles = List.copyOf(profiles);
        this.forced = forced;
    }

    @Override
    public String id() {
        return forced ? "administrator-forced-profile" : "declarative-profile";
    }

    @Override
    public ProviderTier tier() {
        return forced ? ProviderTier.ADMIN_FORCE : ProviderTier.PROFILE;
    }

    @Override
    public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        for (var profile : profiles) {
            if (!profile.targetMod().equals("*") && !profile.targetMod().equals(descriptor.ownerMod())) {
                continue;
            }
            if (!conditionsMatch(profile)) {
                continue;
            }
            for (var rule : profile.rules()) {
                if (rule.action().overrideNativePolymer() != forced || !rule.matches(descriptor)) {
                    continue;
                }
                if (forced && !context.overrideNativePolymer()) {
                    continue;
                }
                var decision = toDecision(profile, rule.action(), descriptor);
                if (decision != null) {
                    return Optional.of(decision);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public String explainNotApplicable(ContentDescriptor descriptor, MappingContext context) {
        if (forced && !context.overrideNativePolymer()) {
            for (var profile : profiles) {
                if ((!profile.targetMod().equals("*") && !profile.targetMod().equals(descriptor.ownerMod()))
                        || !conditionsMatch(profile)) {
                    continue;
                }
                for (var rule : profile.rules()) {
                    if (rule.action().overrideNativePolymer() && rule.matches(descriptor)) {
                        return "matched " + profile.id()
                                + " but main config override_native_polymer is false";
                    }
                }
            }
        }
        return CompatibilityProvider.super.explainNotApplicable(descriptor, context);
    }

    private static boolean conditionsMatch(CompatibilityProfile profile) {
        var loader = FabricLoader.getInstance();
        if (profile.optionalDependencies().stream().anyMatch(dependency -> !loader.isModLoaded(dependency))) {
            return false;
        }
        if (profile.targetMod().equals("*") || profile.targetVersion().equals("*")) {
            return true;
        }
        var container = loader.getModContainer(profile.targetMod());
        if (container.isEmpty()) {
            return false;
        }
        try {
            return VersionPredicate.parse(profile.targetVersion()).test(container.get().getMetadata().getVersion());
        } catch (VersionParsingException exception) {
            throw new IllegalArgumentException("Invalid target_version in profile " + profile.id(), exception);
        }
    }

    private MappingDecision toDecision(CompatibilityProfile profile, CompatibilityProfile.Action action,
                                       ContentDescriptor descriptor) {
        var reasons = List.of("Matched declarative profile " + profile.id(),
                "Matched action " + action.type(),
                forced ? "Native override was explicitly enabled by both profile and main config"
                        : "Native Polymer mappings remain protected");
        return switch (action.type()) {
            case "disable_auto_mapping" -> new MappingDecision(descriptor, MappingStatus.UNSUPPORTED, id(), "none",
                    "profile-disabled", "", 1, 100, reasons, List.of(), List.of(),
                    "Automatic mapping disabled by profile " + profile.id());
            case "item_carrier_category" -> {
                requireType(descriptor, ContentType.ITEM, action.type());
                var carrier = ITEM_CARRIERS.get(action.value());
                yield decision(descriptor, profile, "profile-item-carrier", carrier, reasons);
            }
            case "block_strategy" -> {
                requireSafeFullCube(descriptor, action.type());
                yield decision(descriptor, profile, action.value(), "polymer:allocated-full-block", reasons);
            }
            case "vanilla_fallback_state" -> {
                requireSafeFullCube(descriptor, action.type());
                yield decision(descriptor, profile, "vanilla-fallback-state", action.value(), reasons);
            }
            case "entity_replacement" -> classification(descriptor, ContentType.ENTITY,
                    "future-entity-replacement", action.value(), reasons);
            case "gui_classification" -> classification(descriptor, ContentType.GUI,
                    "gui-" + action.value(), "", reasons);
            default -> throw new IllegalStateException("Validated action became unknown: " + action.type());
        };
    }

    private MappingDecision decision(ContentDescriptor descriptor, CompatibilityProfile profile,
                                     String strategy, String carrier, List<String> reasons) {
        return new MappingDecision(descriptor, MappingStatus.PROFILE, id(), "polymer", strategy, carrier,
                0.95, 10, reasons, List.of(), List.of("Profile supplied by " + profile.id()), null);
    }

    private MappingDecision classification(ContentDescriptor descriptor, ContentType expected,
                                           String strategy, String carrier, List<String> reasons) {
        requireType(descriptor, expected, strategy);
        return new MappingDecision(descriptor, MappingStatus.UNSUPPORTED, id(), "classification-only", strategy,
                carrier, 1, 100, reasons, List.of(),
                List.of("The profile request is recorded for a future explicit backend"),
                "PolyMc Reborn 0.1 has no automatic " + expected.name().toLowerCase() + " projection backend");
    }

    private static void requireSafeFullCube(ContentDescriptor descriptor, String action) {
        requireType(descriptor, ContentType.BLOCK, action);
        if (!descriptor.booleanAttribute("full_cube") || !descriptor.booleanAttribute("stable_shape")
                || descriptor.booleanAttribute("has_block_entity")) {
            throw new IllegalArgumentException(action + " requires a stable full cube without a block entity");
        }
    }

    private static void requireType(ContentDescriptor descriptor, ContentType expected, String action) {
        if (descriptor.contentType() != expected) {
            throw new IllegalArgumentException(action + " cannot target " + descriptor.contentType());
        }
    }
}
