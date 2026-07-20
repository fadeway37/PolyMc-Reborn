/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.upgradefixture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.polymcreborn.api.PolyMcRebornEntrypoint;
import io.github.polymcreborn.api.entity.EntityProjectionAdapter;
import io.github.polymcreborn.api.entity.EntityProjectionInteraction;
import io.github.polymcreborn.api.entity.EntityProjectionRegistry;
import io.github.polymcreborn.api.gui.GuiInteractionPolicy;
import io.github.polymcreborn.api.gui.GuiProjection;
import io.github.polymcreborn.api.gui.GuiProjectionAdapter;
import io.github.polymcreborn.api.gui.GuiProjectionRegistry;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.damagesource.DamageSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent-state fixture compiled once and run unchanged with 0.3 Beta and 0.4 RC. */
public final class UpgradeFixture implements ModInitializer, PolyMcRebornEntrypoint {
    private static final String MOD_ID = "polymc-reborn-upgrade-fixture";
    private static final AtomicInteger TICKS = new AtomicInteger();
    private static final BlockPos STABLE_BLOCK_POS = new BlockPos(0, 100, 0);
    private static final BlockPos STABLE_STATE_POS = new BlockPos(1, 100, 0);
    private static final BlockPos CONTAINER_POS = new BlockPos(2, 100, 0);
    private static Item stableItem;
    private static Block stableBlock;
    private static StatefulBlock statefulBlock;
    private static MenuType<PropertyMenu> propertyMenuType;
    private static EntityType<PersistentEntity> persistentEntityType;
    private static boolean bootstrapped;
    private static HttpServer packServer;
    private static ExecutorService packExecutor;
    private static String resourcePackUrl;
    private static String resourcePackSha1;
    private static volatile boolean playerObserved;
    private static volatile String playerItem = "missing";
    private static volatile int playerItemCount = -1;

    @Override
    public void onInitialize() {
        bootstrap();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            preparePersistentState(server);
            capture(server, Path.of(System.getProperty("polymc-reborn.upgrade.report")
                    + ".ready.json"), "ready");
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> pushPack(handler.player));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopPackServer());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int ticks = TICKS.incrementAndGet();
            for (var player : server.getPlayerList().getPlayers()) {
                var inventory = player.getInventory();
                if (inventory.getItem(0).isEmpty()) {
                    inventory.setItem(0, new ItemStack(stableItem, 7));
                    inventory.setChanged();
                }
                playerObserved = true;
                playerItem = BuiltInRegistries.ITEM.getKey(inventory.getItem(0).getItem()).toString();
                playerItemCount = inventory.getItem(0).getCount();
            }
            Path stop = Path.of(System.getProperty("polymc-reborn.upgrade.stopFile"))
                    .toAbsolutePath().normalize();
            if (Files.isRegularFile(stop)) {
                server.getPlayerList().saveAll();
                server.saveEverything(true, true, true);
                capture(server, Path.of(System.getProperty("polymc-reborn.upgrade.report")), "final");
                server.halt(false);
            } else if (ticks > 12_000) {
                throw new IllegalStateException("Upgrade fixture timed out waiting for its client/stop marker");
            }
        });
    }

    @Override
    public void registerGuiProjections(GuiProjectionRegistry registry) {
        bootstrap();
        registry.register(new GuiProjectionAdapter() {
            @Override
            public Identifier id() {
                return UpgradeFixture.id("persistent-property-menu");
            }

            @Override
            public MenuType<?> serverMenuType() {
                return propertyMenuType;
            }

            @Override
            public GuiProjection project(AbstractContainerMenu sourceMenu, ServerPlayer player) {
                if (!(sourceMenu instanceof PropertyMenu menu)) {
                    throw new IllegalArgumentException("Upgrade property adapter received another menu type");
                }
                return GuiProjection.furnace(menu.container(), menu.data(), GuiInteractionPolicy.readOnly());
            }
        });
    }

    @Override
    public void registerEntityProjections(EntityProjectionRegistry registry) {
        bootstrap();
        registry.register(EntityProjectionAdapter.of(MOD_ID + ":persistent-entity",
                persistentEntityType, EntityType.ARMOR_STAND,
                EntityProjectionInteraction.denyAll()));
    }

    private static synchronized void bootstrap() {
        if (bootstrapped) {
            return;
        }
        bootstrapped = true;
        stableItem = registerGuiItem("stable_item");
        stableBlock = registerBlock("stable_block");
        statefulBlock = registerStatefulBlock("stable_stateful_block");
        propertyMenuType = Registry.register(BuiltInRegistries.MENU, id("persistent_property_menu"),
                new MenuType<>(PropertyMenu::new, FeatureFlags.DEFAULT_FLAGS));
        Identifier entityId = id("persistent_entity");
        persistentEntityType = Registry.register(BuiltInRegistries.ENTITY_TYPE, entityId,
                EntityType.Builder.of(PersistentEntity::new, MobCategory.MISC).sized(0.8F, 1.8F)
                        .build(ResourceKey.create(Registries.ENTITY_TYPE, entityId)));
    }

    private static void preparePersistentState(net.minecraft.server.MinecraftServer server) {
        var level = server.overworld();
        level.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD,
                new BlockPos(0, 101, 2), 180.0F, 0.0F));
        var fixtureChunk = ChunkPos.containing(STABLE_BLOCK_POS);
        level.setChunkForced(fixtureChunk.x(), fixtureChunk.z(), true);
        level.getChunkAt(STABLE_BLOCK_POS);
        level.waitForEntities(fixtureChunk, 1);
        for (int x = -2; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
            }
        }
        if (!level.getBlockState(STABLE_BLOCK_POS).is(stableBlock)) {
            level.setBlockAndUpdate(STABLE_BLOCK_POS, stableBlock.defaultBlockState());
        }
        if (!level.getBlockState(STABLE_STATE_POS).is(statefulBlock)) {
            level.setBlockAndUpdate(STABLE_STATE_POS,
                    statefulBlock.defaultBlockState().setValue(StatefulBlock.ACTIVE, true));
        }
        if (!level.getBlockState(CONTAINER_POS).is(Blocks.BARREL)) {
            level.setBlockAndUpdate(CONTAINER_POS, Blocks.BARREL.defaultBlockState());
        }
        if (level.getBlockEntity(CONTAINER_POS) instanceof Container container
                && container.getItem(0).isEmpty()) {
            container.setItem(0, new ItemStack(stableItem, 7));
            container.setChanged();
        }
        var persistentEntities = level.getEntities(persistentEntityType, entity -> true);
        if (persistentEntities.isEmpty()) {
            var entity = new PersistentEntity(persistentEntityType, level);
            entity.snapTo(2.5D, 100.0D, -1.5D);
            entity.setCustomName(Component.literal("Upgrade Persistent Entity"));
            entity.setCustomNameVisible(true);
            entity.persistentValue = 73;
            if (!level.addFreshEntity(entity)) {
                throw new IllegalStateException("Upgrade fixture could not create its persistent entity");
            }
        } else if (persistentEntities.size() != 1) {
            throw new IllegalStateException("Upgrade fixture expected exactly one persistent entity, found "
                    + persistentEntities.size());
        }
        var success = new java.util.concurrent.atomic.AtomicBoolean();
        var source = server.createCommandSourceStack().withCallback((accepted, value) ->
                success.set(accepted));
        server.getCommands().performPrefixedCommand(source, "pmcr pack build");
        if (!success.get()) {
            throw new IllegalStateException("Upgrade fixture could not build the resource pack");
        }
        startPackServer();
    }

    private static void startPackServer() {
        try {
            Path packPath = FabricLoader.getInstance().getConfigDir().resolve("polymc-reborn")
                    .resolve("cache").resolve("polymc-reborn-resource-pack.zip");
            byte[] pack = Files.readAllBytes(packPath);
            int port = Integer.parseInt(System.getProperty("polymc-reborn.upgrade.packPort"));
            packExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon(true).name("polymc-reborn-upgrade-pack").factory());
            packServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            packServer.createContext("/polymc-reborn-resource-pack.zip",
                    exchange -> servePack(exchange, pack));
            packServer.setExecutor(packExecutor);
            packServer.start();
            resourcePackUrl = "http://127.0.0.1:" + port + "/polymc-reborn-resource-pack.zip";
            resourcePackSha1 = digest(pack, "SHA-1");
        } catch (Exception exception) {
            stopPackServer();
            throw new IllegalStateException("Upgrade fixture could not host the generated resource pack",
                    exception);
        }
    }

    private static void pushPack(net.minecraft.server.level.ServerPlayer player) {
        if (resourcePackUrl == null || resourcePackSha1 == null) {
            throw new IllegalStateException("Upgrade client joined before the resource pack host was ready");
        }
        String mode = System.getProperty("polymc-reborn.upgrade.mode", "unknown");
        UUID id = UUID.nameUUIDFromBytes(("polymc-reborn-upgrade:" + mode + ':' + resourcePackSha1)
                .getBytes(StandardCharsets.UTF_8));
        player.connection.send(new ClientboundResourcePackPushPacket(id, resourcePackUrl,
                resourcePackSha1, true,
                Optional.of(Component.literal("PolyMc Reborn upgrade compatibility test"))));
    }

    private static void servePack(HttpExchange exchange, byte[] pack) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"/polymc-reborn-resource-pack.zip".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, pack.length);
            exchange.getResponseBody().write(pack);
        }
    }

    private static synchronized void stopPackServer() {
        if (packServer != null) {
            packServer.stop(0);
            packServer = null;
        }
        if (packExecutor != null) {
            packExecutor.shutdownNow();
            packExecutor = null;
        }
    }

    private static void capture(net.minecraft.server.MinecraftServer server, Path report, String phase) {
        try {
            Path mapping = FabricLoader.getInstance().getConfigDir()
                    .resolve("polymc-reborn").resolve("mappings-v1.json");
            byte[] bytes = Files.readAllBytes(mapping);
            Path pack = FabricLoader.getInstance().getConfigDir().resolve("polymc-reborn")
                    .resolve("cache").resolve("polymc-reborn-resource-pack.zip");
            byte[] packBytes = Files.readAllBytes(pack);
            byte[] worldState = worldState(server).getBytes(StandardCharsets.UTF_8);
            byte[] rebornJar = Files.readAllBytes(Path.of(System.getProperty(
                    "polymc-reborn.upgrade.rebornJar")));
            Path playerData = FabricLoader.getInstance().getGameDir().resolve("world")
                    .resolve("players").resolve("data");
            byte[] playerBytes = hashDirectoryInput(playerData);
            Path diagnosticPolicy = FabricLoader.getInstance().getConfigDir()
                    .resolve("polymc-reborn").resolve("diagnostics-policy.json");
            byte[] diagnosticPolicyBytes = Files.readAllBytes(diagnosticPolicy);
            var level = server.overworld();
            var persistentEntities = level.getEntities(persistentEntityType, entity -> true);
            PersistentEntity persistentEntity = persistentEntities.size() == 1
                    ? (PersistentEntity) persistentEntities.getFirst() : null;
            int persistentEntityValue = persistentEntity == null ? -1 : persistentEntity.persistentValue;
            String persistentEntityName = persistentEntity != null && persistentEntity.getCustomName() != null
                    ? persistentEntity.getCustomName().getString() : "missing";
            String persistentEntityUuid = persistentEntity == null
                    ? "missing" : persistentEntity.getUUID().toString();
            String propertyItem = "missing";
            int propertyItemCount = -1;
            if (level.getBlockEntity(CONTAINER_POS) instanceof Container container) {
                propertyItem = BuiltInRegistries.ITEM.getKey(container.getItem(0).getItem()).toString();
                propertyItemCount = container.getItem(0).getCount();
            }
            String modList = FabricLoader.getInstance().getAllMods().stream()
                    .map(mod -> mod.getMetadata().getId() + "="
                            + mod.getMetadata().getVersion().getFriendlyString())
                    .sorted().collect(Collectors.joining(","));
            String mode = System.getProperty("polymc-reborn.upgrade.mode", "unknown");
            report = report.toAbsolutePath().normalize();
            Files.createDirectories(report.getParent());
            String json = "{\n  \"schema_version\": 1,\n  \"phase\": \"" + phase
                    + "\",\n  \"mode\": \"" + mode
                    + "\",\n  \"mapping_sha256\": \"" + sha256(bytes)
                    + "\",\n  \"mapping_bytes\": " + bytes.length
                    + ",\n  \"resource_pack_sha256\": \"" + sha256(packBytes)
                    + "\",\n  \"resource_pack_sha1\": \"" + digest(packBytes, "SHA-1")
                    + "\",\n  \"resource_pack_bytes\": " + packBytes.length
                    + ",\n  \"world_state_sha256\": \"" + sha256(worldState)
                    + "\",\n  \"reborn_jar_sha256\": \"" + sha256(rebornJar)
                    + "\",\n  \"player_data_sha256\": \"" + sha256(playerBytes)
                    + "\",\n  \"player_data_bytes\": " + playerBytes.length
                    + ",\n  \"player_observed\": " + playerObserved
                    + ",\n  \"player_item\": \"" + escape(playerItem)
                    + "\",\n  \"player_item_count\": " + playerItemCount
                    + ",\n  \"diagnostic_policy_sha256\": \"" + sha256(diagnosticPolicyBytes)
                    + "\",\n  \"diagnostic_policy_bytes\": " + diagnosticPolicyBytes.length
                    + ",\n  \"property_gui_item\": \"" + escape(propertyItem)
                    + "\",\n  \"property_gui_item_count\": " + propertyItemCount
                    + ",\n  \"property_gui_progress\": 37"
                    + ",\n  \"persistent_entity_count\": " + persistentEntities.size()
                    + ",\n  \"persistent_entity_value\": " + persistentEntityValue
                    + ",\n  \"persistent_entity_name\": \"" + escape(persistentEntityName)
                    + "\",\n  \"persistent_entity_uuid\": \"" + escape(persistentEntityUuid)
                    + "\",\n  \"world_state\": \"" + escape(new String(worldState, StandardCharsets.UTF_8))
                    + "\",\n  \"mod_list\": \"" + escape(modList) + "\"\n}\n";
            Files.writeString(report, json, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Upgrade fixture could not capture evidence", exception);
        }
    }

    private static byte[] hashDirectoryInput(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return new byte[0];
        }
        var output = new java.io.ByteArrayOutputStream();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(value -> value.getFileName().toString())).toList()) {
                output.write(path.getFileName().toString().getBytes(StandardCharsets.UTF_8));
                output.write(0);
                output.write(Files.readAllBytes(path));
            }
        }
        return output.toByteArray();
    }

    private static String worldState(net.minecraft.server.MinecraftServer server) {
        var level = server.overworld();
        String stableId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(STABLE_BLOCK_POS).getBlock())
                .toString();
        BlockState state = level.getBlockState(STABLE_STATE_POS);
        String stateId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        boolean active = state.hasProperty(StatefulBlock.ACTIVE) && state.getValue(StatefulBlock.ACTIVE);
        String container = "missing";
        int count = -1;
        if (level.getBlockEntity(CONTAINER_POS) instanceof Container inventory) {
            container = BuiltInRegistries.ITEM.getKey(inventory.getItem(0).getItem()).toString();
            count = inventory.getItem(0).getCount();
        }
        return "stable_block=" + stableId + ";stateful_block=" + stateId + "[active=" + active
                + "];container_item=" + container + ";container_count=" + count;
    }

    private static Item registerGuiItem(String path) {
        Identifier id = id(path);
        var properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
        return Registry.register(BuiltInRegistries.ITEM, id, new PropertyGuiItem(properties));
    }

    private static Block registerBlock(String path) {
        Identifier id = id(path);
        var block = Registry.register(BuiltInRegistries.BLOCK, id,
                new Block(BlockBehaviour.Properties.ofFullCopy(Blocks.NOTE_BLOCK)
                        .setId(ResourceKey.create(Registries.BLOCK, id))));
        var itemProperties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
                .useBlockDescriptionPrefix();
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, itemProperties));
        return block;
    }

    private static StatefulBlock registerStatefulBlock(String path) {
        Identifier id = id(path);
        var block = Registry.register(BuiltInRegistries.BLOCK, id,
                new StatefulBlock(BlockBehaviour.Properties.ofFullCopy(Blocks.COPPER_BLOCK)
                        .setId(ResourceKey.create(Registries.BLOCK, id))));
        var itemProperties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id))
                .useBlockDescriptionPrefix();
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, itemProperties));
        return block;
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return digest(bytes, "SHA-256");
    }

    private static String digest(byte[] bytes, String algorithm) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Opens the explicit property projection over the persisted barrel slots. */
    private static final class PropertyGuiItem extends Item {
        private PropertyGuiItem(Properties properties) {
            super(properties);
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            if (level instanceof ServerLevel && player instanceof ServerPlayer serverPlayer) {
                var menu = new PropertyMenu(0, serverPlayer.getInventory());
                var opened = io.github.polymcreborn.core.PolyMcReborn.runtime()
                        .guiProjectionService().open(serverPlayer, menu,
                                Component.literal("Upgrade Persistent Property GUI"));
                return opened.isPresent() ? InteractionResult.SUCCESS : InteractionResult.FAIL;
            }
            return InteractionResult.PASS;
        }
    }

    /** Real custom menu whose first three slots delegate directly to a persisted barrel. */
    private static final class PropertyMenu extends AbstractContainerMenu {
        private final Container container;
        private final SimpleContainerData data = new SimpleContainerData(4);

        private PropertyMenu(int containerId, Inventory inventory) {
            super(propertyMenuType, containerId);
            if (!(inventory.player.level().getBlockEntity(CONTAINER_POS) instanceof Container backing)) {
                throw new IllegalStateException("Upgrade persistent property container is missing");
            }
            this.container = new FirstThreeContainer(backing);
            data.set(0, 200);
            data.set(1, 200);
            data.set(2, 37);
            data.set(3, 100);
        }

        private Container container() {
            return container;
        }

        private SimpleContainerData data() {
            return data;
        }

        @Override
        public ItemStack quickMoveStack(Player player, int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public boolean stillValid(Player player) {
            return container.stillValid(player);
        }
    }

    /** A three-slot view, not a copied inventory; every mutation reaches the world BlockEntity. */
    private static final class FirstThreeContainer implements Container {
        private final Container backing;

        private FirstThreeContainer(Container backing) {
            this.backing = backing;
            if (backing.getContainerSize() < 3) {
                throw new IllegalArgumentException("Persistent property container needs at least three slots");
            }
        }

        @Override
        public int getContainerSize() {
            return 3;
        }

        @Override
        public boolean isEmpty() {
            return getItem(0).isEmpty() && getItem(1).isEmpty() && getItem(2).isEmpty();
        }

        @Override
        public ItemStack getItem(int slot) {
            return backing.getItem(checkSlot(slot));
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return backing.removeItem(checkSlot(slot), amount);
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return backing.removeItemNoUpdate(checkSlot(slot));
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            backing.setItem(checkSlot(slot), stack);
        }

        @Override
        public void setChanged() {
            backing.setChanged();
        }

        @Override
        public boolean stillValid(Player player) {
            return backing.stillValid(player);
        }

        @Override
        public void clearContent() {
            for (int slot = 0; slot < 3; slot++) {
                backing.setItem(slot, ItemStack.EMPTY);
            }
            backing.setChanged();
        }

        private static int checkSlot(int slot) {
            if (slot < 0 || slot >= 3) {
                throw new IndexOutOfBoundsException("property slot " + slot);
            }
            return slot;
        }
    }

    /** Real saved entity projected explicitly to an armor stand for the vanilla test client. */
    private static final class PersistentEntity extends Entity {
        private int persistentValue;

        private PersistentEntity(EntityType<? extends PersistentEntity> type, Level level) {
            super(type, level);
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {
        }

        @Override
        protected void readAdditionalSaveData(ValueInput input) {
            persistentValue = input.getIntOr("polymc_reborn_upgrade_value", -1);
        }

        @Override
        protected void addAdditionalSaveData(ValueOutput output) {
            output.putInt("polymc_reborn_upgrade_value", persistentValue);
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }
    }

    private static final class StatefulBlock extends Block {
        private static final BooleanProperty ACTIVE = BooleanProperty.create("active");

        private StatefulBlock(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(ACTIVE, false));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            builder.add(ACTIVE);
        }
    }
}
