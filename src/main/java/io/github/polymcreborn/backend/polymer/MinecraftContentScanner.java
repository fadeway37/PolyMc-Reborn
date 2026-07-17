/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.other.PolymerMenuUtils;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads built-in registries once during static planning; never from a tick or packet hot path. */
public final class MinecraftContentScanner {
    public List<ContentDescriptor> scan() {
        var descriptors = new ArrayList<ContentDescriptor>();
        scanItems(descriptors);
        scanBlocks(descriptors);
        scanEntities(descriptors);
        scanMenus(descriptors);
        return descriptors.stream().sorted().toList();
    }

    private void scanItems(List<ContentDescriptor> output) {
        BuiltInRegistries.ITEM.keySet().stream().sorted().forEach(id -> {
            if (isVanilla(id.getNamespace())) {
                return;
            }
            Item item = BuiltInRegistries.ITEM.getValue(id);
            var attributes = baseAttributes(id.getNamespace());
            attributes.put("native_polymer", Boolean.toString(
                    PolymerSyncedObject.getSyncedObject(BuiltInRegistries.ITEM, item) instanceof PolymerItem));
            attributes.put("components_bound", Boolean.toString(item.builtInRegistryHolder().areComponentsBound()));
            attributes.put("item_category", classifyItem(item));
            attributes.put("custom_renderer", "false");
            output.add(ContentDescriptor.of(id.toString(), owner(id.getNamespace()), ContentType.ITEM, attributes));
        });
    }

    private void scanBlocks(List<ContentDescriptor> output) {
        BuiltInRegistries.BLOCK.keySet().stream().sorted().forEach(id -> {
            if (isVanilla(id.getNamespace())) {
                return;
            }
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            var analysis = analyzeBlock(block);

            var attributes = baseAttributes(id.getNamespace());
            attributes.put("native_polymer", Boolean.toString(
                    PolymerSyncedObject.getSyncedObject(BuiltInRegistries.BLOCK, block) instanceof PolymerBlock));
            attributes.put("full_cube", Boolean.toString(analysis.fullCube()));
            attributes.put("stable_shape", Boolean.toString(analysis.stableShape()));
            attributes.put("has_block_entity", Boolean.toString(analysis.hasBlockEntity()));
            attributes.put("state_count", Integer.toString(analysis.stateCount()));
            attributes.put("shape_analysis_failed", Boolean.toString(analysis.failed()));
            if (analysis.failed()) {
                attributes.put("shape_analysis_error", analysis.failureType());
            }
            analysis.blockProperties().forEach((name, values) ->
                    attributes.put("block_property." + name, values));
            output.add(ContentDescriptor.of(id.toString(), owner(id.getNamespace()), ContentType.BLOCK, attributes));
        });
    }

    private static BlockAnalysis analyzeBlock(Block block) {
        try {
            var states = block.getStateDefinition().getPossibleStates();
            boolean hasBlockEntity = states.stream().anyMatch(state -> state.hasBlockEntity());
            var properties = new java.util.TreeMap<String, String>();
            block.getStateDefinition().getProperties().stream()
                    .sorted(java.util.Comparator.comparing(
                            net.minecraft.world.level.block.state.properties.Property::getName))
                    .forEach(property -> properties.put(property.getName(), propertyValues(property)));
            try {
                boolean fullCube = states.stream().allMatch(state ->
                        Block.isShapeFullBlock(state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
                                CollisionContext.empty()))
                                && Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO,
                                CollisionContext.empty())));
                boolean stableShape = !block.hasDynamicShape() && states.stream().noneMatch(state ->
                        state.isSignalSource() || state.hasAnalogOutputSignal());
                return new BlockAnalysis(fullCube, stableShape, hasBlockEntity, states.size(), properties,
                        false, "");
            } catch (RuntimeException exception) {
                return failedAnalysis(hasBlockEntity, states.size(), properties, exception);
            }
        } catch (RuntimeException exception) {
            // If even state metadata is unsafe to inspect, assume the most conservative block shape and BE facts.
            return failedAnalysis(true, 0, Map.of(), exception);
        }
    }

    private static BlockAnalysis failedAnalysis(boolean hasBlockEntity, int stateCount,
                                                Map<String, String> properties, RuntimeException exception) {
        return new BlockAnalysis(false, false, hasBlockEntity, stateCount, properties, true,
                exception.getClass().getName());
    }

    private void scanEntities(List<ContentDescriptor> output) {
        BuiltInRegistries.ENTITY_TYPE.keySet().stream().sorted().forEach(id -> {
            if (isVanilla(id.getNamespace())) {
                return;
            }
            var entityType = BuiltInRegistries.ENTITY_TYPE.getValue(id);
            var attributes = baseAttributes(id.getNamespace());
            attributes.put("native_polymer", Boolean.toString(PolymerEntityUtils.isPolymerEntityType(entityType)));
            output.add(ContentDescriptor.of(id.toString(), owner(id.getNamespace()), ContentType.ENTITY, attributes));
        });
    }

    private void scanMenus(List<ContentDescriptor> output) {
        BuiltInRegistries.MENU.keySet().stream().sorted().forEach(id -> {
            if (isVanilla(id.getNamespace())) {
                return;
            }
            var menuType = BuiltInRegistries.MENU.getValue(id);
            var attributes = baseAttributes(id.getNamespace());
            attributes.put("native_polymer", Boolean.toString(PolymerMenuUtils.isPolymerType(menuType)));
            output.add(ContentDescriptor.of(id.toString(), owner(id.getNamespace()), ContentType.GUI, attributes));
        });
    }

    static String classifyItem(Item item) {
        if (item instanceof BlockItem) {
            return "block_item";
        }
        final var components = componentsIfBound(item);
        if (components == null) {
            return "material";
        }
        var consumable = components.get(DataComponents.CONSUMABLE);
        if (consumable != null) {
            return consumable.animation() == ItemUseAnimation.DRINK ? "drink" : "food";
        }
        if (components.has(DataComponents.EQUIPPABLE)) {
            return "armor";
        }
        if (components.has(DataComponents.BLOCKS_ATTACKS)) {
            return "shield";
        }
        if (components.has(DataComponents.CHARGED_PROJECTILES)) {
            return "crossbow";
        }
        if (components.has(DataComponents.TOOL) || components.has(DataComponents.WEAPON)) {
            return "tool";
        }
        return "material";
    }

    private static net.minecraft.core.component.DataComponentMap componentsIfBound(Item item) {
        // 26.1 binds item default components after the overlay registration boundary.
        return item.builtInRegistryHolder().areComponentsBound() ? item.components() : null;
    }

    private static <T extends Comparable<T>> String propertyValues(
            net.minecraft.world.level.block.state.properties.Property<T> property) {
        return property.getPossibleValues().stream().map(property::getName).sorted()
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static Map<String, String> baseAttributes(String namespace) {
        var attributes = new HashMap<String, String>();
        FabricLoader.getInstance().getModContainer(namespace).ifPresent(container ->
                attributes.put("owner_version", container.getMetadata().getVersion().getFriendlyString()));
        return attributes;
    }

    private static String owner(String namespace) {
        return FabricLoader.getInstance().getModContainer(namespace)
                .map(container -> container.getMetadata().getId())
                .orElse(namespace);
    }

    private static boolean isVanilla(String namespace) {
        return namespace.equals("minecraft") || namespace.equals("brigadier");
    }

    private record BlockAnalysis(boolean fullCube, boolean stableShape, boolean hasBlockEntity, int stateCount,
                                 Map<String, String> blockProperties, boolean failed, String failureType) {
        private BlockAnalysis {
            blockProperties = Map.copyOf(new java.util.TreeMap<>(blockProperties));
        }
    }
}
