/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.consumer;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.PolyMcRebornEntrypoint;
import io.github.polymcreborn.api.ProviderTier;
import io.github.polymcreborn.api.entity.EntityProjectionAdapter;
import io.github.polymcreborn.api.entity.EntityProjectionRegistry;
import io.github.polymcreborn.api.gui.GuiInteractionPolicy;
import io.github.polymcreborn.api.gui.GuiProjection;
import io.github.polymcreborn.api.gui.GuiProjectionAdapter;
import io.github.polymcreborn.api.gui.GuiProjectionRegistry;
import io.github.polymcreborn.api.gui.GuiSlotMapping;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.network.syncher.SynchedEntityData;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/** Independent server Mod proving that the published API is consumable without a project dependency. */
public final class ApiConsumerMod implements ModInitializer, PolyMcRebornEntrypoint {
    public static final String MOD_ID = "polymc-reborn-api-consumer";
    private static final Identifier ITEM_ID = id("consumer_item");
    private static final Identifier BLOCK_ID = id("consumer_block");
    private static Item item;
    private static Block block;
    private static MenuType<ConsumerMenu> menuType;
    private static EntityType<ConsumerEntity> entityType;
    private static boolean initialized;

    @Override
    public synchronized void onInitialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        item = Registry.register(BuiltInRegistries.ITEM, ITEM_ID,
                new Item(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, ITEM_ID))));
        block = Registry.register(BuiltInRegistries.BLOCK, BLOCK_ID,
                new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
                        .setId(ResourceKey.create(Registries.BLOCK, BLOCK_ID))));
        Registry.register(BuiltInRegistries.ITEM, BLOCK_ID,
                new BlockItem(block, new Item.Properties()
                        .setId(ResourceKey.create(Registries.ITEM, BLOCK_ID))
                        .useBlockDescriptionPrefix()));
        menuType = Registry.register(BuiltInRegistries.MENU, id("consumer_menu"),
                new MenuType<>(ConsumerMenu::new, FeatureFlags.DEFAULT_FLAGS));
        Identifier entityId = id("consumer_entity");
        entityType = Registry.register(BuiltInRegistries.ENTITY_TYPE, entityId,
                EntityType.Builder.of(ConsumerEntity::new, MobCategory.MISC).sized(0.6F, 1.6F)
                        .build(ResourceKey.create(Registries.ENTITY_TYPE, entityId)));
    }

    @Override
    public void registerCompatibility(ExtensionRegistry registry) {
        onInitialize();
        registry.registerProvider(new ConsumerProvider());
    }

    @Override
    public void registerResources(ResourceRegistry resources) {
        resources.register(MOD_ID, sink -> {
            // The actual item/block assets are ordinary JAR resources and are collected by the
            // decision dependency traversal. This separate path proves the public contributor
            // contract without attempting to overwrite resources owned by the same Mod JAR.
            put(sink, "assets/" + MOD_ID + "/polymc-reborn/consumer-contribution.json",
                    "{\"schema_version\":1,\"consumer\":\"" + MOD_ID + "\"}");
        });
    }

    @Override
    public void registerGuiProjections(GuiProjectionRegistry registry) {
        onInitialize();
        registry.register(new GuiProjectionAdapter() {
            @Override
            public Identifier id() {
                return ApiConsumerMod.id("consumer-gui-adapter");
            }

            @Override
            public MenuType<?> serverMenuType() {
                return menuType;
            }

            @Override
            public GuiProjection project(AbstractContainerMenu sourceMenu, ServerPlayer player) {
                if (!(sourceMenu instanceof ConsumerMenu consumer)) {
                    throw new IllegalArgumentException("Consumer GUI adapter received an unexpected menu");
                }
                return new GuiProjection(consumer.container, 1, GuiSlotMapping.identity(9),
                        GuiInteractionPolicy.safeStandard());
            }
        });
    }

    @Override
    public void registerEntityProjections(EntityProjectionRegistry registry) {
        onInitialize();
        registry.register(EntityProjectionAdapter.of(MOD_ID + ":consumer-entity-adapter",
                entityType, EntityType.ARMOR_STAND,
                io.github.polymcreborn.api.entity.EntityProjectionInteraction.denyAll()));
    }

    private static void put(io.github.polymcreborn.api.ResourceContributor.ResourceSink sink,
            String path, String value) {
        sink.put(path, value.getBytes(StandardCharsets.UTF_8), MOD_ID);
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static final class ConsumerProvider implements CompatibilityProvider {
        @Override
        public String id() {
            return MOD_ID + ":provider";
        }

        @Override
        public ProviderTier tier() {
            return ProviderTier.EXPLICIT;
        }

        @Override
        public Optional<MappingDecision> evaluate(io.github.polymcreborn.api.ContentDescriptor descriptor,
                MappingContext context) {
            if (descriptor.registryId().equals(ITEM_ID.toString())
                    && descriptor.contentType() == ContentType.ITEM) {
                return Optional.of(decision(descriptor, "semantic-item-material", "minecraft:paper",
                        List.of("assets/" + MOD_ID + "/items/consumer_item.json")));
            }
            if (descriptor.registryId().equals(BLOCK_ID.toString())
                    && descriptor.contentType() == ContentType.BLOCK) {
                return Optional.of(decision(descriptor, "textured-full-cube", "",
                        List.of("assets/" + MOD_ID + "/blockstates/consumer_block.json")));
            }
            return Optional.empty();
        }

        private MappingDecision decision(io.github.polymcreborn.api.ContentDescriptor descriptor,
                String strategy, String carrier, List<String> resources) {
            return new MappingDecision(descriptor, MappingStatus.EXPLICIT, id(), "polymer", strategy,
                    carrier, 0.98, 5,
                    List.of("The independently compiled API consumer explicitly selected this adapter"),
                    resources, List.of(), null);
        }
    }

    private static final class ConsumerMenu extends AbstractContainerMenu {
        private final SimpleContainer container = new SimpleContainer(9);

        private ConsumerMenu(int containerId, Inventory inventory) {
            super(menuType, containerId);
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

    private static final class ConsumerEntity extends Entity {
        private ConsumerEntity(EntityType<? extends ConsumerEntity> type, Level level) {
            super(type, level);
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
            return false;
        }
    }
}
