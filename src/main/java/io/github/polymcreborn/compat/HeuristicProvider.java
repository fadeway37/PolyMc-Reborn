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
import java.util.Map;
import java.util.Optional;

/** Conservative built-in semantic mappings. It deliberately refuses complex blocks, entities, and GUIs. */
public final class HeuristicProvider implements CompatibilityProvider {
    private static final Map<String, String> ITEM_CARRIERS = Map.ofEntries(
            Map.entry("food", "minecraft:apple"),
            Map.entry("drink", "minecraft:honey_bottle"),
            Map.entry("tool", "minecraft:iron_pickaxe"),
            Map.entry("armor", "minecraft:iron_chestplate"),
            Map.entry("bow", "minecraft:bow"),
            Map.entry("crossbow", "minecraft:crossbow"),
            Map.entry("shield", "minecraft:shield"),
            Map.entry("throwable", "minecraft:snowball"),
            Map.entry("block_item", "minecraft:stone"),
            Map.entry("material", "minecraft:paper")
    );

    @Override
    public String id() {
        return "builtin-heuristics";
    }

    @Override
    public ProviderTier tier() {
        return ProviderTier.HEURISTIC;
    }

    @Override
    public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
        return switch (descriptor.contentType()) {
            case ITEM -> itemDecision(descriptor);
            case BLOCK -> blockDecision(descriptor);
            case ENTITY, GUI -> Optional.empty();
        };
    }

    private Optional<MappingDecision> itemDecision(ContentDescriptor descriptor) {
        var category = descriptor.attributes().getOrDefault("item_category", "material");
        var carrier = ITEM_CARRIERS.get(category);
        if (carrier == null) {
            return Optional.empty();
        }
        var confidence = "material".equals(category) ? 0.55 : 0.82;
        var warnings = new java.util.ArrayList<String>();
        if (descriptor.booleanAttribute("custom_renderer")) {
            warnings.add("Client-only custom renderers cannot be projected");
        }
        boolean deferredComponents = !descriptor.booleanAttribute("components_bound")
                && !"block_item".equals(category);
        if (deferredComponents) {
            warnings.add("Item default components were unavailable during planning; the persisted generic "
                    + "material carrier is retained rather than changing representation in a packet hot path");
        }
        var strategy = deferredComponents ? "semantic-item-material-unbound" : "semantic-item-" + category;
        return Optional.of(new MappingDecision(descriptor, MappingStatus.HEURISTIC, id(), "polymer",
                strategy, carrier, confidence, "material".equals(category) ? 35 : 15,
                List.of("Server item semantics were classified as " + category,
                        "A conservative vanilla carrier was selected while the real server Item remains unchanged"),
                List.of("assets/" + namespace(descriptor.registryId())), List.copyOf(warnings), null));
    }

    private Optional<MappingDecision> blockDecision(ContentDescriptor descriptor) {
        if (!descriptor.booleanAttribute("full_cube") || !descriptor.booleanAttribute("stable_shape")) {
            return Optional.empty();
        }
        if (descriptor.booleanAttribute("has_block_entity")) {
            return Optional.empty();
        }
        if (descriptor.attributes().containsKey("breaking_semantics_safe")
                && !descriptor.booleanAttribute("breaking_semantics_safe")) {
            return Optional.empty();
        }
        int stateCount = Integer.parseInt(descriptor.attributes().getOrDefault("state_count", "1"));
        String registryId = descriptor.registryId();
        String namespace = namespace(registryId);
        String path = registryId.substring(registryId.indexOf(':') + 1);
        var resources = new java.util.ArrayList<String>();
        resources.add("assets/" + namespace);
        if (stateCount > 1) {
            resources.add("assets/" + namespace + "/blockstates/" + path + ".json");
        }
        return Optional.of(new MappingDecision(descriptor, MappingStatus.HEURISTIC, id(), "polymer",
                "textured-full-cube", "polymer:allocated-full-block", 0.9, stateCount > 1 ? 8 : 5,
                List.of("Collision and outline shapes are stable full cubes",
                        "No block entity is associated with the block",
                        "Breaking speed and correct-tool requirements do not outrun the vanilla carrier",
                        stateCount > 1
                                ? "Every safe server state will require a distinct, resolvable Polymer carrier"
                                : "Polymer Blocks will allocate a safe textured full-block carrier"),
                resources, List.of(), null));
    }

    private static String namespace(String identifier) {
        return identifier.substring(0, identifier.indexOf(':'));
    }
}
