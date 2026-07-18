/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.core.PolyMcReborn;
import io.github.polymcreborn.api.ContentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only server controller for the isolated two-process client playtest. */
public final class PlaytestFixtureEntrypoint implements ModInitializer {
    private static final BlockPos PLACE_TARGET = new BlockPos(0, 100, -1);
    private static final BlockPos SIMPLE_TARGET = new BlockPos(1, 100, -1);
    private static HttpServer packServer;
    private static ExecutorService packExecutor;
    private static String resourcePackUrl;
    private static Path stopFile;
    private static Path readyFile;
    private static Path reportDirectory;
    private static boolean targetWasPlaced;
    private static boolean simpleTargetWasPlaced;
    private static String resourcePackSha256 = "missing";
    private static String resourcePackSha1 = "missing";
    private static String initialMappingSha256 = "missing";
    private static final List<String> COMMANDS = List.of(
            "pmcr about",
            "pmcr scan",
            "pmcr report json",
            "pmcr report markdown",
            "pmcr why polymc-reborn-gametest:basic_item",
            "pmcr why polymc-reborn-gametest:stateful_block",
            "pmcr why polymc-reborn-gametest:fixture_menu",
            "pmcr why polymc-reborn-gametest:fixture_entity",
            "pmcr stats");

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("polymc-reborn.playtest.enabled")) {
            return;
        }
        FixtureContent.bootstrap();
        stopFile = controlledPath("polymc-reborn.playtest.stopFile", "stop.request");
        readyFile = controlledPath("polymc-reborn.playtest.readyFile", "server-ready.json");
        reportDirectory = Path.of(requiredProperty("polymc-reborn.playtest.reportDir"))
                .toAbsolutePath().normalize();

        ServerLifecycleEvents.SERVER_STARTED.register(PlaytestFixtureEntrypoint::start);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> join(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            observeInventory(handler.player);
            PlaytestProbe.DISCONNECT_COUNT.incrementAndGet();
        });
        ServerTickEvents.END_SERVER_TICK.register(PlaytestFixtureEntrypoint::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            writeReport(server, "server-stopping");
            stopPackServer();
        });
    }

    private static void start(MinecraftServer server) {
        try {
            Files.deleteIfExists(stopFile);
            setupWorld(server);
            var result = PolyMcReborn.runtime().packService().build();
            PolyMcReborn.runtime().writePackReport(result);
            byte[] pack = Files.readAllBytes(PolyMcReborn.runtime().packService().output());
            int port = Integer.parseInt(requiredProperty("polymc-reborn.playtest.packPort"));
            packExecutor = Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("polymc-reborn-playtest-pack").factory());
            packServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            packServer.createContext("/polymc-reborn-resource-pack.zip", exchange -> servePack(exchange, pack));
            packServer.setExecutor(packExecutor);
            packServer.start();
            resourcePackUrl = "http://127.0.0.1:" + port + "/polymc-reborn-resource-pack.zip";
            String sha1 = digest("SHA-1", pack);
            resourcePackSha256 = result.sha256();
            resourcePackSha1 = sha1;
            initialMappingSha256 = mappingStoreSha256();
            String productionJarName = requiredProperty("polymc-reborn.playtest.productionJarName");
            String productionJarSha256 = requiredProperty("polymc-reborn.playtest.productionJarSha256")
                    .toLowerCase(java.util.Locale.ROOT);
            if (!productionJarName.matches("polymc-reborn-.+\\.jar")
                    || productionJarName.contains("/") || productionJarName.contains("\\")
                    || !productionJarSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("Production JAR evidence properties are malformed");
            }
            var ready = "{\n"
                    + "  \"schema_version\": 1,\n"
                    + "  \"minecraft_version\": \"26.1.2\",\n"
                    + "  \"server_port\": " + server.getPort() + ",\n"
                    + "  \"production_jar_name\": \"" + jsonEscape(productionJarName) + "\",\n"
                    + "  \"production_jar_sha256\": \"" + productionJarSha256 + "\",\n"
                    + "  \"resource_pack_sha256\": \"" + result.sha256() + "\",\n"
                    + "  \"resource_pack_sha1\": \"" + sha1 + "\"\n"
                    + "}\n";
            executeAdminCommands(server);
            AtomicFiles.write(readyFile, ready.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Could not start the isolated playtest fixture", exception);
        }
    }

    private static void executeAdminCommands(MinecraftServer server) {
        for (String command : COMMANDS) {
            var callbackInvoked = new AtomicBoolean();
            var commandSucceeded = new AtomicBoolean();
            var resultValue = new AtomicInteger(Integer.MIN_VALUE);
            var source = server.createCommandSourceStack().withCallback((success, value) -> {
                callbackInvoked.set(true);
                commandSucceeded.set(success);
                resultValue.set(value);
            });
            var parsed = server.getCommands().getDispatcher().parse(command, source);
            if (parsed.getReader().canRead()) {
                throw new IllegalStateException("Playtest admin command did not parse completely: " + command);
            }
            server.getCommands().performPrefixedCommand(source, command);
            if (!callbackInvoked.get() || !commandSucceeded.get()) {
                throw new IllegalStateException("Playtest admin command failed: " + command
                        + " (callback=" + callbackInvoked.get() + ", value=" + resultValue.get() + ")");
            }
            PlaytestProbe.COMMAND_COUNT.incrementAndGet();
        }
    }

    private static void setupWorld(MinecraftServer server) {
        var level = server.overworld();
        var gameRules = level.getGameRules();
        gameRules.set(GameRules.ADVANCE_TIME, false, server);
        gameRules.set(GameRules.ADVANCE_WEATHER, false, server);
        gameRules.set(GameRules.SPAWN_MOBS, false, server);
        gameRules.set(GameRules.NATURAL_HEALTH_REGENERATION, false, server);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
                for (int y = 100; y <= 104; y++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        level.setBlockAndUpdate(new BlockPos(-2, 100, 0), FixtureContent.STATEFUL_BLOCK.defaultBlockState()
                .setValue(FixtureContent.StatefulFullBlock.ACTIVE, false));
        level.setBlockAndUpdate(new BlockPos(-1, 100, 0), FixtureContent.STATEFUL_BLOCK.defaultBlockState()
                .setValue(FixtureContent.StatefulFullBlock.ACTIVE, true));
        level.setBlockAndUpdate(new BlockPos(-3, 100, 0), FixtureContent.FULL_BLOCK.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(0, 100, 0), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(new BlockPos(1, 100, 0), Blocks.STONE.defaultBlockState());
        var entity = new FixtureContent.FixtureEntity(FixtureContent.ENTITY_TYPE, level);
        // Keep the surrogate inside the vanilla survival entity-pick range. The
        // adapter's server-side distance guard is intentionally not a client
        // reach extension, so a farther fixture can never produce a real
        // EntityHitResult even though its projection is visible and tracked.
        entity.snapTo(1.5D, 100.0D, -2.5D);
        entity.setCustomName(Component.literal("Projected Fixture Entity"));
        entity.setCustomNameVisible(true);
        entity.setGlowingTag(true);
        if (!level.addFreshEntity(entity)) {
            throw new IllegalStateException("Could not spawn explicit entity projection fixture");
        }
    }

    private static void join(ServerPlayer player) {
        int joins = PlaytestProbe.JOIN_COUNT.incrementAndGet();
        player.setGameMode(GameType.SURVIVAL);
        player.teleportTo(0.5D, 100.0D, -4.5D);
        player.forceSetRotation(0.0F, true, 0.0F, true);
        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0.0F);
        var inventory = player.getInventory();
        if (joins == 1) {
            inventory.clearContent();
            inventory.setItem(0, namedStack(FixtureContent.BASIC_ITEM, 1,
                    "PolyMc Reborn Fixture Item", "Vanilla carrier; real server item"));
            inventory.setItem(1, namedStack(FixtureContent.STATEFUL_BLOCK.asItem(), 8,
                    "PolyMc Reborn Stateful Block", "Two deterministic client states"));
            inventory.setItem(2, namedStack(FixtureContent.TOOL_ITEM, 1,
                    "PolyMc Reborn Fixture Pickaxe", "Durability remains server-authoritative"));
            inventory.setItem(3, namedStack(FixtureContent.FOOD_ITEM, 4,
                    "PolyMc Reborn Fixture Food", "Semantic food carrier"));
            inventory.setItem(4, namedStack(FixtureContent.FULL_BLOCK.asItem(), 4,
                    "PolyMc Reborn Full Block", "Simple full-cube projection"));
            inventory.setSelectedSlot(0);
        }
        if (resourcePackUrl == null || resourcePackSha1 == null || resourcePackSha256 == null) {
            throw new IllegalStateException("Playtest client joined before resource pack host was ready");
        }
        // Resource-pack IDs identify individual protocol pushes, not the immutable ZIP content.
        // Use a distinct deterministic ID per connection so a reconnect cannot deduplicate the
        // second push after Minecraft has removed the first connection's active server packs.
        var pushId = UUID.nameUUIDFromBytes(("polymc-reborn-playtest:" + resourcePackSha256
                + ":connection:" + joins).getBytes(StandardCharsets.UTF_8));
        var packPacket = new ClientboundResourcePackPushPacket(pushId, resourcePackUrl,
                resourcePackSha1, true,
                Optional.of(Component.literal("PolyMc Reborn isolated compatibility playtest")));
        player.connection.send(packPacket);
        PlaytestProbe.RESOURCE_PACK_PUSH_COUNT.incrementAndGet();
        player.sendSystemMessage(Component.literal("PolyMc Reborn isolated playtest session " + joins));
    }

    private static ItemStack namedStack(net.minecraft.world.item.Item item, int count,
                                        String name, String lore) {
        var stack = new ItemStack(item, count);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.literal(name));
        stack.set(net.minecraft.core.component.DataComponents.LORE,
                new net.minecraft.world.item.component.ItemLore(java.util.List.of(Component.literal(lore))));
        return stack;
    }

    private static void tick(MinecraftServer server) {
        var state = server.overworld().getBlockState(PLACE_TARGET);
        if (state.is(FixtureContent.STATEFUL_BLOCK)) {
            targetWasPlaced = true;
            PlaytestProbe.placedBlockObserved = true;
        } else if (targetWasPlaced && state.isAir()) {
            PlaytestProbe.brokenBlockObserved = true;
        }
        var simpleState = server.overworld().getBlockState(SIMPLE_TARGET);
        if (simpleState.is(FixtureContent.FULL_BLOCK)) {
            simpleTargetWasPlaced = true;
            PlaytestProbe.simpleBlockPlacedObserved = true;
        } else if (simpleTargetWasPlaced && simpleState.isAir()) {
            PlaytestProbe.simpleBlockBrokenObserved = true;
        }
        server.getPlayerList().getPlayers().forEach(PlaytestFixtureEntrypoint::observeInventory);
        if (Files.exists(stopFile)) {
            writeReport(server, "stop-requested");
            server.halt(false);
        }
    }

    private static void servePack(HttpExchange exchange, byte[] pack) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())
                    || !"/polymc-reborn-resource-pack.zip".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            PlaytestProbe.RESOURCE_PACK_REQUEST_COUNT.incrementAndGet();
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

    private static synchronized void writeReport(MinecraftServer server, String reason) {
        if (reportDirectory == null) {
            return;
        }
        try {
            Files.createDirectories(reportDirectory);
            int foodRemaining = PlaytestProbe.FOOD_REMAINING.get();
            int toolDamage = PlaytestProbe.MAX_TOOL_DAMAGE.get();
            String finalMappingSha256 = mappingStoreSha256();
            boolean mappingStable = !"missing".equals(initialMappingSha256)
                    && initialMappingSha256.equals(finalMappingSha256);
            String finalPackSha256 = currentPackSha256();
            boolean packStable = resourcePackSha256.equals(finalPackSha256);
            int guiSessions = PolyMcReborn.runtime().guiProjectionService().activeSessionCount();
            int entitySessions = PolyMcReborn.runtime().entityProjectionBackend().activeSessionCount();
            String productionJarName = requiredProperty("polymc-reborn.playtest.productionJarName");
            String productionJarSha256 = requiredProperty("polymc-reborn.playtest.productionJarSha256")
                    .toLowerCase(java.util.Locale.ROOT);
            boolean productionJarValid = productionJarName.matches("polymc-reborn-.+\\.jar")
                    && !productionJarName.contains("/") && !productionJarName.contains("\\")
                    && productionJarSha256.matches("[0-9a-f]{64}");
            long projectedInteractions = PolyMcReborn.runtime().entityProjectionBackend()
                    .acceptedInteractionCount();
            boolean passed = PlaytestProbe.JOIN_COUNT.get() == 2
                    && PlaytestProbe.DISCONNECT_COUNT.get() == 2
                    && PlaytestProbe.placedBlockObserved
                    && PlaytestProbe.brokenBlockObserved
                    && PlaytestProbe.simpleBlockPlacedObserved
                    && PlaytestProbe.simpleBlockBrokenObserved
                    && PlaytestProbe.stateToggleObserved
                    && PlaytestProbe.GUI_OPEN_COUNT.get() == 3
                    && PlaytestProbe.GUI_CLOSE_COUNT.get() == 3
                    && PlaytestProbe.guiInventoryIntegrity
                    && PlaytestProbe.ENTITY_USE_COUNT.get() == 1
                    && PlaytestProbe.ENTITY_ATTACK_COUNT.get() == 1
                    && projectedInteractions == 2
                    && PlaytestProbe.semanticUseObserved
                    && foodRemaining == 3
                    && toolDamage == 2
                    && PlaytestProbe.COMMAND_COUNT.get() == COMMANDS.size()
                    && PlaytestProbe.RESOURCE_PACK_PUSH_COUNT.get() == 2
                    && PlaytestProbe.RESOURCE_PACK_REQUEST_COUNT.get() == 2
                    && guiSessions == 0
                    && entitySessions == 1
                    && productionJarValid
                    && mappingStable
                    && packStable;
            String json = "{\n"
                    + "  \"schema_version\": 1,\n"
                    + "  \"result\": \"" + (passed ? "passed" : "failed") + "\",\n"
                    + "  \"reason\": \"" + reason + "\",\n"
                    + "  \"completed_at\": \"" + Instant.now() + "\",\n"
                    + "  \"join_count\": " + PlaytestProbe.JOIN_COUNT.get() + ",\n"
                    + "  \"disconnect_count\": " + PlaytestProbe.DISCONNECT_COUNT.get() + ",\n"
                    + "  \"placed_block_observed\": " + PlaytestProbe.placedBlockObserved + ",\n"
                    + "  \"broken_block_observed\": " + PlaytestProbe.brokenBlockObserved + ",\n"
                    + "  \"simple_block_placed_observed\": " + PlaytestProbe.simpleBlockPlacedObserved + ",\n"
                    + "  \"simple_block_broken_observed\": " + PlaytestProbe.simpleBlockBrokenObserved + ",\n"
                    + "  \"state_toggle_observed\": " + PlaytestProbe.stateToggleObserved + ",\n"
                    + "  \"gui_open_count\": " + PlaytestProbe.GUI_OPEN_COUNT.get() + ",\n"
                    + "  \"gui_close_count\": " + PlaytestProbe.GUI_CLOSE_COUNT.get() + ",\n"
                    + "  \"gui_inventory_integrity\": " + PlaytestProbe.guiInventoryIntegrity + ",\n"
                    + "  \"entity_use_count\": " + PlaytestProbe.ENTITY_USE_COUNT.get() + ",\n"
                    + "  \"entity_attack_count\": " + PlaytestProbe.ENTITY_ATTACK_COUNT.get() + ",\n"
                    + "  \"entity_interaction_callbacks\": " + projectedInteractions + ",\n"
                    + "  \"semantic_use_observed\": " + PlaytestProbe.semanticUseObserved + ",\n"
                    + "  \"tool_damage\": " + toolDamage + ",\n"
                    + "  \"food_remaining\": " + foodRemaining + ",\n"
                    + "  \"client_profile\": \"VANILLA\",\n"
                    + "  \"admin_command_count\": " + PlaytestProbe.COMMAND_COUNT.get() + ",\n"
                    + "  \"resource_pack_push_count\": "
                    + PlaytestProbe.RESOURCE_PACK_PUSH_COUNT.get() + ",\n"
                    + "  \"resource_pack_request_count\": "
                    + PlaytestProbe.RESOURCE_PACK_REQUEST_COUNT.get() + ",\n"
                    + "  \"gui_active_sessions\": " + guiSessions + ",\n"
                    + "  \"entity_projection_sessions\": " + entitySessions + ",\n"
                    + "  \"production_jar_name\": \"" + jsonEscape(productionJarName) + "\",\n"
                    + "  \"production_jar_sha256\": \"" + productionJarSha256 + "\",\n"
                    + "  \"mapping_store_sha256\": \"" + finalMappingSha256 + "\",\n"
                    + "  \"mapping_store_stable\": " + mappingStable + ",\n"
                    + "  \"resource_pack_sha256\": \"" + resourcePackSha256 + "\",\n"
                    + "  \"resource_pack_sha1\": \"" + resourcePackSha1 + "\",\n"
                    + "  \"resource_pack_stable\": " + packStable + ",\n"
                    + "  \"mapping_decisions\": " + mappingDecisionsJson() + ",\n"
                    + "  \"loaded_server_mods\": " + loadedServerModsJson() + "\n"
                    + "}\n";
            AtomicFiles.write(reportDirectory.resolve("server-state.json"),
                    json.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write playtest server report", exception);
        }
    }

    private static int toolDamage(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(FixtureContent.TOOL_ITEM)) {
                return stack.getDamageValue();
            }
        }
        return -1;
    }

    private static void observeInventory(ServerPlayer player) {
        int damage = toolDamage(player);
        if (damage >= 0) {
            PlaytestProbe.MAX_TOOL_DAMAGE.accumulateAndGet(damage, Math::max);
        }
        int food = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(FixtureContent.FOOD_ITEM)) {
                food += stack.getCount();
            }
        }
        PlaytestProbe.FOOD_REMAINING.set(food);
        if (food < 4) {
            PlaytestProbe.semanticUseObserved = true;
        }
    }

    private static String mappingStoreSha256() {
        var path = FabricLoader.getInstance().getConfigDir()
                .resolve("polymc-reborn").resolve("mappings-v1.json");
        try {
            return Files.exists(path) ? digest("SHA-256", Files.readAllBytes(path)) : "missing";
        } catch (IOException exception) {
            throw new IllegalStateException("Could not hash playtest mapping store", exception);
        }
    }

    private static String currentPackSha256() {
        try {
            return digest("SHA-256", Files.readAllBytes(PolyMcReborn.runtime().packService().output()));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not hash playtest resource pack", exception);
        }
    }

    private static String loadedServerModsJson() {
        var mods = FabricLoader.getInstance().getAllMods().stream()
                .map(container -> container.getMetadata().getId() + "@"
                        + container.getMetadata().getVersion().getFriendlyString())
                .sorted().toList();
        var json = new StringBuilder("[");
        for (int index = 0; index < mods.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(jsonEscape(mods.get(index))).append('"');
        }
        return json.append(']').toString();
    }

    private static String mappingDecisionsJson() {
        record Expected(ContentType type, String registryId) {
        }
        var expected = List.of(
                new Expected(ContentType.ITEM, FixtureContent.MOD_ID + ":food_item"),
                new Expected(ContentType.BLOCK, FixtureContent.MOD_ID + ":full_block"),
                new Expected(ContentType.BLOCK, FixtureContent.MOD_ID + ":stateful_block"),
                new Expected(ContentType.GUI, FixtureContent.MOD_ID + ":fixture_menu"),
                new Expected(ContentType.ENTITY, FixtureContent.MOD_ID + ":fixture_entity"));
        var json = new StringBuilder("{");
        for (int index = 0; index < expected.size(); index++) {
            var key = expected.get(index);
            var decision = PolyMcReborn.runtime().plan().decision(key.type(), key.registryId());
            if (decision == null) {
                throw new IllegalStateException("Missing required playtest mapping decision "
                        + key.type() + ":" + key.registryId());
            }
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(jsonEscape(key.type().name().toLowerCase(java.util.Locale.ROOT)
                            + ":" + key.registryId())).append("\":{")
                    .append("\"status\":\"").append(decision.status()).append("\",")
                    .append("\"provider\":\"").append(jsonEscape(decision.provider())).append("\",")
                    .append("\"backend\":\"").append(jsonEscape(decision.backend())).append("\",")
                    .append("\"strategy\":\"").append(jsonEscape(decision.strategy())).append("\",")
                    .append("\"client_carrier\":\"").append(jsonEscape(decision.clientCarrier()))
                    .append("\"}");
        }
        return json.append('}').toString();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static Path controlledPath(String property, String expectedFileName) {
        var path = Path.of(requiredProperty(property)).toAbsolutePath().normalize();
        if (!path.getFileName().toString().equals(expectedFileName)) {
            throw new IllegalArgumentException(property + " must end with " + expectedFileName);
        }
        return path;
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property " + name);
        }
        return value;
    }

    private static String digest(String algorithm, byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }
}
