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
import net.minecraft.world.entity.Entity;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
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
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;


/** Registry fixtures spanning every MVP discovery category. */
public final class FixtureContent {
    public static final String MOD_ID = "polymc-reborn-gametest";

    public static Item BASIC_ITEM;
    public static Item FOOD_ITEM;
    public static Item TOOL_ITEM;
    public static Item LEGACY_ITEM;
    public static Item NATIVE_ITEM;
    public static Item PROPERTY_GUI_ITEM;
    public static Block FULL_BLOCK;
    public static Block STATEFUL_BLOCK;
    public static Block NON_FULL_BLOCK;
    public static Block THROWING_SHAPE_BLOCK;
    public static Block BLOCK_ENTITY_BLOCK;
    public static Block LEGACY_BLOCK;
    public static Block NATIVE_BLOCK;
    public static BlockEntityType<FixtureBlockEntity> BLOCK_ENTITY_TYPE;
    public static EntityType<FixtureEntity> ENTITY_TYPE;
    public static EntityType<FixtureEntity> UNSUPPORTED_ENTITY_TYPE;
    public static MenuType<FixtureMenu> MENU_TYPE;
    public static MenuType<UnsupportedFixtureMenu> UNSUPPORTED_MENU_TYPE;
    public static MenuType<PropertyFixtureMenu> PROPERTY_MENU_TYPE;

    private static boolean bootstrapped;
    private static final java.util.Map<java.util.UUID, FixtureContainer> GUI_CONTAINERS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, PropertyState> PROPERTY_STATES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private FixtureContent() {
    }

    public static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;

        BASIC_ITEM = registerItem("basic_item", new GuiOpenerItem(itemProperties("basic_item")));
        FOOD_ITEM = registerItem("food_item", new SemanticFoodItem(itemProperties("food_item").food(
                new FoodProperties.Builder().nutrition(4).saturationModifier(0.4F).build())));
        TOOL_ITEM = registerItem("tool_item", new Item(itemProperties("tool_item")
                .pickaxe(ToolMaterial.IRON, 1.0F, -2.8F)));
        LEGACY_ITEM = registerItem("legacy_item", new Item(itemProperties("legacy_item")));
        NATIVE_ITEM = registerItem("native_item", new Item(itemProperties("native_item")));
        PROPERTY_GUI_ITEM = registerItem("property_gui_item",
                new PropertyGuiOpenerItem(itemProperties("property_gui_item")));

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
                EntityType.Builder.of(FixtureEntity::new, MobCategory.MISC)
                        .sized(0.8F, 1.8F).build(entityKey));
        var unsupportedEntityId = id("unsupported_entity");
        var unsupportedEntityKey = ResourceKey.create(Registries.ENTITY_TYPE, unsupportedEntityId);
        UNSUPPORTED_ENTITY_TYPE = Registry.register(BuiltInRegistries.ENTITY_TYPE, unsupportedEntityId,
                EntityType.Builder.of(FixtureEntity::new, MobCategory.MISC)
                        .sized(0.8F, 1.8F).build(unsupportedEntityKey));
        MENU_TYPE = Registry.register(BuiltInRegistries.MENU, id("fixture_menu"),
                new MenuType<>(FixtureMenu::new, FeatureFlags.DEFAULT_FLAGS));
        UNSUPPORTED_MENU_TYPE = Registry.register(BuiltInRegistries.MENU, id("unsupported_menu"),
                new MenuType<>(UnsupportedFixtureMenu::new, FeatureFlags.DEFAULT_FLAGS));
        PROPERTY_MENU_TYPE = Registry.register(BuiltInRegistries.MENU, id("property_menu"),
                new MenuType<>(PropertyFixtureMenu::new, FeatureFlags.DEFAULT_FLAGS));

        PolymerItemUtils.registerOverlay(NATIVE_ITEM, (stack, context) -> Items.STICK);
        PolymerBlockUtils.registerOverlay(NATIVE_BLOCK,
                (state, context) -> Blocks.STONE.defaultBlockState());
    }

    private static Item.Properties itemProperties(String path) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(path)));
    }

    private static BlockBehaviour.Properties blockProperties(String path) {
        // The Polymer full-block pool exposes note-block states. Matching the
        // authoritative fixture hardness/tool requirements to that carrier is
        // part of the safety contract: otherwise the vanilla client can finish
        // its break animation before the real server block is breakable.
        return BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK)
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

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position,
                                                    Player player,
                                                    net.minecraft.world.phys.BlockHitResult hit) {
            if (!level.isClientSide()) {
                level.setBlockAndUpdate(position, state.cycle(ACTIVE));
                PlaytestProbe.stateToggleObserved = true;
            }
            return InteractionResult.SUCCESS;
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

    /** Opens the explicit standard-container projection; the item remains a real server item. */
    public static final class GuiOpenerItem extends Item {
        public GuiOpenerItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (level instanceof ServerLevel && player instanceof ServerPlayer serverPlayer) {
                var opened = io.github.polymcreborn.core.PolyMcReborn.runtime().guiProjectionService().open(
                        serverPlayer, new FixtureMenu(0, serverPlayer.getInventory()),
                        net.minecraft.network.chat.Component.literal("PolyMc Reborn Safe Container"));
                if (opened.isPresent()) {
                    PlaytestProbe.GUI_OPEN_COUNT.incrementAndGet();
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        }
    }

    /** Opens a real three-slot/property source through the explicit furnace adapter. */
    public static final class PropertyGuiOpenerItem extends Item {
        public PropertyGuiOpenerItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (level instanceof ServerLevel && player instanceof ServerPlayer serverPlayer) {
                var opened = io.github.polymcreborn.core.PolyMcReborn.runtime().guiProjectionService().open(
                        serverPlayer, new PropertyFixtureMenu(0, serverPlayer.getInventory()),
                        net.minecraft.network.chat.Component.literal("PolyMc Reborn Property Furnace"));
                if (opened.isPresent()) {
                    PlaytestProbe.PROPERTY_GUI_OPEN_COUNT.incrementAndGet();
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        }
    }

    public static void tickPropertyMenus() {
        PROPERTY_STATES.values().forEach(PropertyState::tick);
    }

    public static int fixtureContainerCount() {
        return GUI_CONTAINERS.size();
    }

    /** Deterministic food-like fixture whose real server action is observable without timing ambiguity. */
    public static final class SemanticFoodItem extends Item {
        public SemanticFoodItem(Properties properties) {
            super(properties);
        }

        @Override
        public ItemStack finishUsingItem(ItemStack stack, Level level,
                                         net.minecraft.world.entity.LivingEntity user) {
            int countBefore = stack.getCount();
            var remaining = super.finishUsingItem(stack, level, user);
            if (!level.isClientSide() && remaining.getCount() == countBefore - 1) {
                PlaytestProbe.semanticUseObserved = true;
            }
            return remaining;
        }
    }

    /** Real custom server entity used by both GameTest and the isolated client playtest. */
    public static final class FixtureEntity extends Entity {
        public FixtureEntity(EntityType<? extends FixtureEntity> type, Level level) {
            super(type, level);
        }

        @Override
        public void tick() {
            super.tick();
            double phase = tickCount * 0.05D;
            setPos(1.5D + Math.sin(phase) * 0.2D, 100.0D, -2.5D + Math.cos(phase) * 0.2D);
            setYRot((tickCount * 3.0F) % 360.0F);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {
        }

        @Override
        protected void readAdditionalSaveData(ValueInput input) {
        }

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            PlaytestProbe.ENTITY_ATTACK_COUNT.incrementAndGet();
            return true;
        }

        public void projectedUse() {
            PlaytestProbe.ENTITY_USE_COUNT.incrementAndGet();
        }
    }

    public static final class FixtureMenu extends AbstractContainerMenu {
        private final FixtureContainer container;

        public FixtureMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory) {
            super(MENU_TYPE, containerId);
            this.container = GUI_CONTAINERS.computeIfAbsent(inventory.player.getUUID(), ignored -> {
                var created = new FixtureContainer(inventory.player);
                created.setItem(0, new ItemStack(Items.DIAMOND, 16));
                created.setItem(1, new ItemStack(Items.EMERALD, 8));
                return created;
            });
        }

        public FixtureContainer container() {
            return container;
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

    public static final class PropertyFixtureMenu extends AbstractContainerMenu {
        private final PropertyState state;

        public PropertyFixtureMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory) {
            super(PROPERTY_MENU_TYPE, containerId);
            this.state = PROPERTY_STATES.computeIfAbsent(inventory.player.getUUID(), ignored ->
                    new PropertyState(inventory.player));
        }

        public net.minecraft.world.Container container() {
            return state.container;
        }

        public net.minecraft.world.inventory.ContainerData data() {
            return state.data;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return state.container.stillValid(player);
        }
    }

    private static final class PropertyState {
        private final SimpleContainer container = new SimpleContainer(3);
        private final net.minecraft.world.inventory.SimpleContainerData data =
                new net.minecraft.world.inventory.SimpleContainerData(4);
        private boolean completed;

        private PropertyState(Player player) {
            container.setItem(0, new ItemStack(Items.RAW_IRON));
            container.setItem(1, new ItemStack(Items.COAL));
            data.set(0, 200);
            data.set(1, 200);
            data.set(2, 0);
            data.set(3, 100);
        }

        private void tick() {
            if (completed) {
                return;
            }
            int progress = Math.min(100, data.get(2) + 1);
            data.set(0, Math.max(0, data.get(0) - 1));
            data.set(2, progress);
            PlaytestProbe.PROPERTY_TICK_COUNT.incrementAndGet();
            if (progress == 100) {
                container.setItem(0, ItemStack.EMPTY);
                container.setItem(1, ItemStack.EMPTY);
                container.setItem(2, new ItemStack(Items.IRON_INGOT));
                completed = true;
                PlaytestProbe.PROPERTY_COMPLETION_COUNT.incrementAndGet();
            }
            container.setChanged();
        }
    }

    /** A discovered custom menu with no projection adapter; it must stay server-only. */
    public static final class UnsupportedFixtureMenu extends AbstractContainerMenu {
        public UnsupportedFixtureMenu(int containerId, net.minecraft.world.entity.player.Inventory inventory) {
            super(UNSUPPORTED_MENU_TYPE, containerId);
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return true;
        }
    }

    public static final class FixtureContainer extends SimpleContainer {
        private final java.util.UUID owner;

        public FixtureContainer(Player owner) {
            super(27);
            this.owner = owner.getUUID();
        }

        @Override
        public void stopOpen(net.minecraft.world.entity.ContainerUser user) {
            super.stopOpen(user);
            if (user instanceof Player player && player.getUUID().equals(owner)) {
                PlaytestProbe.GUI_CLOSE_COUNT.incrementAndGet();
                int diamonds = countItem(this, Items.DIAMOND) + countItem(player.getInventory(), Items.DIAMOND);
                int emeralds = countItem(this, Items.EMERALD) + countItem(player.getInventory(), Items.EMERALD);
                PlaytestProbe.guiInventoryIntegrity = diamonds == 16 && emeralds == 8;
            }
        }

        private static int countItem(net.minecraft.world.Container container, Item item) {
            int count = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                var stack = container.getItem(slot);
                if (stack.is(item)) {
                    count += stack.getCount();
                }
            }
            return count;
        }
    }
}
