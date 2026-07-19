/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.upgradefixture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;

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

/** API-neutral content fixture that can run unchanged with both 0.2 and 0.3. */
public final class UpgradeFixture implements ModInitializer {
    private static final String MOD_ID = "polymc-reborn-upgrade-fixture";
    private static final AtomicInteger TICKS = new AtomicInteger();
    private static final BlockPos STABLE_BLOCK_POS = new BlockPos(0, 100, 0);
    private static final BlockPos STABLE_STATE_POS = new BlockPos(1, 100, 0);
    private static final BlockPos CONTAINER_POS = new BlockPos(2, 100, 0);
    private static Item stableItem;
    private static Block stableBlock;
    private static StatefulBlock statefulBlock;
    private static HttpServer packServer;
    private static ExecutorService packExecutor;
    private static String resourcePackUrl;
    private static String resourcePackSha1;
    private static volatile boolean playerObserved;
    private static volatile String playerItem = "missing";
    private static volatile int playerItemCount = -1;

    @Override
    public void onInitialize() {
        stableItem = registerItem("stable_item");
        stableBlock = registerBlock("stable_block");
        statefulBlock = registerStatefulBlock("stable_stateful_block");
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

    private static void preparePersistentState(net.minecraft.server.MinecraftServer server) {
        var level = server.overworld();
        level.setRespawnData(LevelData.RespawnData.of(Level.OVERWORLD,
                new BlockPos(0, 101, 2), 180.0F, 0.0F));
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
                    + ",\n  \"world_state\": \"" + escape(new String(worldState, StandardCharsets.UTF_8))
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

    private static Item registerItem(String path) {
        Identifier id = id(path);
        var properties = new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id));
        return Registry.register(BuiltInRegistries.ITEM, id, new Item(properties));
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
