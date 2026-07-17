/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import eu.pb4.polymer.core.api.block.PolymerBlockUtils;
import eu.pb4.polymer.core.api.item.PolymerItemUtils;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Registry fixtures spanning every MVP discovery category. */
public final class FixtureContent {
    public static final String MOD_ID = "polymc-reborn-gametest";

    public static Item BASIC_ITEM;
    public static Item FOOD_ITEM;
    public static Item TOOL_ITEM;
    public static Item LEGACY_ITEM;
    public static Item NATIVE_ITEM;
    public static Block FULL_BLOCK;
    public static Block STATEFUL_BLOCK;
    public static Block NON_FULL_BLOCK;
    public static Block THROWING_SHAPE_BLOCK;
    public static Block BLOCK_ENTITY_BLOCK;
    public static Block LEGACY_BLOCK;
    public static Block NATIVE_BLOCK;
    public static BlockEntityType<FixtureBlockEntity> BLOCK_ENTITY_TYPE;
    public static EntityType<?> ENTITY_TYPE;
    public static MenuType<FixtureMenu> MENU_TYPE;

    private static boolean bootstrapped;

    private FixtureContent() {
    }

    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;

        BASIC_ITEM = registerItem("basic_item", new Item(itemProperties("basic_item")));
        FOOD_ITEM = registerItem("food_item", new Item(itemProperties("food_item").food(
                new FoodProperties.Builder().nutrition(4).saturationModifier(0.4F).build())));
        TOOL_ITEM = registerItem("tool_item", new Item(itemProperties("tool_item")
                .pickaxe(ToolMaterial.IRON, 1.0F, -2.8F)));
        LEGACY_ITEM = registerItem("legacy_item", new Item(itemProperties("legacy_item")));
        NATIVE_ITEM = registerItem("native_item", new Item(itemProperties("native_item")));

        FULL_BLOCK = registerBlock("full_block", new Block(blockProperties("full_block")));
        STATEFUL_BLOCK = registerBlock("stateful_block",
                new StatefulFullBlock(blockProperties("stateful_block")));
        NON_FULL_BLOCK = registerBlock("non_full_block",
                new SlabBlock(blockProperties("non_full_block")));
        THROWING_SHAPE_BLOCK = registerBlock("throwing_shape_block",
                new ThrowingShapeBlock(blockProperties("throwing_shape_block")));
        ((ThrowingShapeBlock) THROWING_SHAPE_BLOCK).failSubsequentShapeReads();
        BLOCK_ENTITY_BLOCK = registerBlock("block_entity_block",
                new FixtureEntityBlock(blockProperties("block_entity_block")));
        LEGACY_BLOCK = registerBlock("legacy_block", new Block(blockProperties("legacy_block")));
        NATIVE_BLOCK = registerBlock("native_block", new Block(blockProperties("native_block")));

        registerBlockItem("full_block", FULL_BLOCK);
        registerBlockItem("stateful_block", STATEFUL_BLOCK);
        registerBlockItem("non_full_block", NON_FULL_BLOCK);
        registerBlockItem("block_entity_block", BLOCK_ENTITY_BLOCK);
        registerBlockItem("legacy_block", LEGACY_BLOCK);
        registerBlockItem("native_block", NATIVE_BLOCK);

        BLOCK_ENTITY_TYPE = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                id("fixture_block_entity"), FabricBlockEntityTypeBuilder.create(
                        FixtureBlockEntity::new, BLOCK_ENTITY_BLOCK).build());
        var entityId = id("fixture_entity");
        var entityKey = ResourceKey.create(Registries.ENTITY_TYPE, entityId);
        ENTITY_TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, entityId,
                EntityType.Builder.createNothing(MobCategory.MISC).sized(0.5F, 0.5F).build(entityKey));
        MENU_TYPE = Registry.register(BuiltInRegistries.MENU, id("fixture_menu"),
                new MenuType<>(FixtureMenu::new, FeatureFlags.DEFAULT_FLAGS));

        PolymerItemUtils.registerOverlay(NATIVE_ITEM, (stack, context) -> Items.STICK);
        PolymerBlockUtils.registerOverlay(NATIVE_BLOCK,
                (state, context) -> Blocks.STONE.defaultBlockState());
    }

    private static Item.Properties itemProperties(String path) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(path)));
    }

    private static BlockBehaviour.Properties blockProperties(String path) {
        return BlockBehaviour.Properties.ofFullCopy(Blocks.STONE)
                .setId(ResourceKey.create(Registries.BLOCK, id(path)));
    }

    private static Item registerItem(String path, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, id(path), item);
    }

    private static Block registerBlock(String path, Block block) {
        return Registry.register(BuiltInRegistries.BLOCK, id(path), block);
    }

    private static void registerBlockItem(String path, Block block) {
        registerItem(path, new BlockItem(block, itemProperties(path).useBlockDescriptionPrefix()));
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static final class StatefulFullBlock extends Block {
        public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

        public StatefulFullBlock(BlockBehaviour.Properties properties) {
            super(properties);
            registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(ACTIVE);
        }
    }

    public static final class FixtureEntityBlock extends Block implements EntityBlock {
        public FixtureEntityBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        public BlockEntity newBlockEntity(BlockPos position, BlockState state) {
            return new FixtureBlockEntity(position, state);
        }
    }

    /** Deliberately hostile analysis fixture: one broken shape must not abort discovery of other entries. */
    public static final class ThrowingShapeBlock extends Block {
        private boolean failShapeReads;

        public ThrowingShapeBlock(BlockBehaviour.Properties properties) {
            super(properties);
        }

        @Override
        protected net.minecraft.world.phys.shapes.VoxelShape getShape(
                BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos position,
                net.minecraft.world.phys.shapes.CollisionContext context) {
            if (failShapeReads) {
                throw new IllegalStateException("deliberate shape-analysis failure");
            }
            return net.minecraft.world.phys.shapes.Shapes.block();
        }

        private void failSubsequentShapeReads() {
            failShapeReads = true;
        }
    }

    public static final class FixtureBlockEntity extends BlockEntity {
        public FixtureBlockEntity(BlockPos position, BlockState state) {
            super(BLOCK_ENTITY_TYPE, position, state);
        }
    }

    public static final class FixtureMenu extends AbstractContainerMenu {
        public FixtureMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory) {
            super(MENU_TYPE, containerId);
        }

        @Override
        public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }
    }
}
