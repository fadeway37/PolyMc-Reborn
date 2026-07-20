/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.core.PolyMcReborn;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.gui.GuiClickTransaction;
import io.github.polymcreborn.backend.gui.ProjectedContainerMenu;
import io.github.polymcreborn.pack.ResourcePackPolicy;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.lang.management.ManagementFactory;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only server controller for the isolated two-process client playtest. */
public final class PlaytestFixtureEntrypoint implements ModInitializer {
    private static final BlockPos PLACE_TARGET = new BlockPos(0, 100, -1);
    private static final BlockPos SIMPLE_TARGET = new BlockPos(1, 100, -1);
    private static final BlockPos EXTERNAL_TARGET = new BlockPos(2, 100, -1);
    private static HttpServer packServer;
    private static ExecutorService packExecutor;
    private static String resourcePackUrl;
    private static Path stopFile;
    private static Path readyFile;
    private static Path reportDirectory;
    private static boolean targetWasPlaced;
    private static boolean simpleTargetWasPlaced;
    private static boolean externalTargetWasPlaced;
    private static boolean externalBlockPlaced;
    private static boolean externalBlockBroken;
    private static boolean externalItemEquipped;
    private static boolean externalFoodConsumed;
    private static String resourcePackSha256 = "missing";
    private static String resourcePackSha1 = "missing";
    private static String initialMappingSha256 = "missing";
    private static final java.util.Set<UUID> INITIALIZED_PLAYERS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<UUID, FixtureContent.FixtureEntity> SOAK_ENTITIES = new ConcurrentHashMap<>();
    private static final List<String> COMMANDS = List.of(
            "pmcr about",
            "pmcr scan",
            "pmcr report json",
            "pmcr report markdown",
            "pmcr why polymc-reborn-gametest:basic_item",
            "pmcr why polymc-reborn-gametest:stateful_block",
            "pmcr why polymc-reborn-gametest:fixture_menu",
            "pmcr why polymc-reborn-gametest:property_menu",
            "pmcr why polymc-reborn-gametest:fixture_entity",
            "pmcr why polymc-reborn-api-consumer:consumer_item",
            "pmcr diagnostics status",
            "pmcr diagnostics validate",
            "pmcr diagnostics why resource-pack.policy",
            "pmcr diagnostics list polymc-reborn",
            "pmcr support bundle",
            "pmcr support bundle status",
            "pmcr mappings dry-run",
            "pmcr stats");

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("polymc-reborn.playtest.enabled")) {
            return;
        }
        FixtureContent.bootstrap();
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
                dispatcher.register(Commands.literal("polymcreborn-playtest")
                        .then(Commands.literal("dimension-cycle")
                                .requires(source -> source.getEntity() instanceof ServerPlayer)
                                .executes(context -> cycleDimension(
                                        context.getSource().getPlayerOrException())))
                        .then(Commands.literal("reject-transaction")
                                .requires(source -> source.getEntity() instanceof ServerPlayer)
                                .executes(context -> rejectTransaction(
                                        context.getSource().getPlayerOrException())))
                        .then(Commands.literal("projection-spawn")
                                .requires(source -> source.getEntity() instanceof ServerPlayer)
                                .executes(context -> spawnSoakProjection(
                                        context.getSource().getPlayerOrException())))
                        .then(Commands.literal("projection-despawn")
                                .requires(source -> source.getEntity() instanceof ServerPlayer)
                                .executes(context -> despawnSoakProjection(
                                        context.getSource().getPlayerOrException())))));
        stopFile = controlledPath("polymc-reborn.playtest.stopFile", "stop.request");
        readyFile = controlledPath("polymc-reborn.playtest.readyFile", "server-ready.json");
        reportDirectory = Path.of(requiredProperty("polymc-reborn.playtest.reportDir"))
                .toAbsolutePath().normalize();

        ServerLifecycleEvents.SERVER_STARTED.register(PlaytestFixtureEntrypoint::start);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> join(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            observeInventory(handler.player);
            var soakEntity = SOAK_ENTITIES.remove(handler.player.getUUID());
            if (soakEntity != null) {
                soakEntity.discard();
                PlaytestProbe.SOAK_ENTITY_DESPAWNS.incrementAndGet();
            }
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
            executeAdminCommand(server, command);
        }
        if ("long".equals(System.getProperty("polymc-reborn.playtest.soakMode", "none"))) {
            for (int repeat = 0; repeat < 2; repeat++) {
                executeAdminCommand(server, "pmcr support bundle");
                executeAdminCommand(server, "pmcr mappings dry-run");
            }
        }
    }

    private static void executeAdminCommand(MinecraftServer server, String command) {
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
        if ("pmcr support bundle".equals(command)) {
            PlaytestProbe.SUPPORT_BUNDLE_GENERATIONS.incrementAndGet();
        } else if ("pmcr mappings dry-run".equals(command)) {
            PlaytestProbe.MAPPING_DRY_RUNS.incrementAndGet();
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
        level.setBlockAndUpdate(new BlockPos(2, 100, 0), Blocks.STONE.defaultBlockState());
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

    private static int cycleDimension(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return 0;
        }
        ServerLevel target = player.level().dimension() == Level.NETHER
                ? server.overworld() : server.getLevel(Level.NETHER);
        if (target == null) {
            return 0;
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -6; z <= -2; z++) {
                target.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
                for (int y = 100; y <= 103; y++) {
                    target.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
        ServerPlayer teleported = player.teleport(new TeleportTransition(target,
                new Vec3(0.5D, 100.0D, -4.5D), Vec3.ZERO, 0.0F, 0.0F,
                TeleportTransition.DO_NOTHING));
        if (teleported == null) {
            return 0;
        }
        PlaytestProbe.DIMENSION_CHANGE_COUNT.incrementAndGet();
        if (target.dimension() == Level.OVERWORLD) {
            PlaytestProbe.SOAK_TRACKING_CYCLES.incrementAndGet();
        }
        return 1;
    }

    private static int rejectTransaction(ServerPlayer player) {
        if (!(player.containerMenu instanceof ProjectedContainerMenu menu)) {
            return 0;
        }
        long sequence = PlaytestProbe.SOAK_REJECTED_TRANSACTIONS.get() + 1L;
        var claim = new GuiClickTransaction(menu.containerId, menu.getStateId() + 1,
                sequence, Map.of(), ItemStack.EMPTY);
        long started = System.nanoTime();
        var result = menu.transact(claim, 0, 0, ContainerInput.PICKUP, player);
        long elapsed = System.nanoTime() - started;
        if (!result.fullResyncRequired()) {
            return 0;
        }
        PlaytestProbe.REJECTED_TRANSACTION_TOTAL_NANOS.addAndGet(elapsed);
        PlaytestProbe.REJECTED_TRANSACTION_MAX_NANOS.accumulateAndGet(elapsed, Math::max);
        PlaytestProbe.SOAK_REJECTED_TRANSACTIONS.incrementAndGet();
        PlaytestProbe.SOAK_GUI_CYCLES.incrementAndGet();
        return 1;
    }

    private static int spawnSoakProjection(ServerPlayer player) {
        if (!"long".equals(System.getProperty("polymc-reborn.playtest.soakMode", "none"))
                || SOAK_ENTITIES.containsKey(player.getUUID())) {
            return 0;
        }
        ServerLevel level = (ServerLevel) player.level();
        var entity = new FixtureContent.FixtureEntity(FixtureContent.ENTITY_TYPE, level);
        entity.snapTo(player.getX() + 1.0D, player.getY(), player.getZ() + 1.0D);
        entity.setCustomName(Component.literal("Projected Soak Entity"));
        entity.setCustomNameVisible(false);
        if (!level.addFreshEntity(entity)
                || PolyMcReborn.runtime().entityProjectionBackend()
                .sessionGeneration(entity.getUUID()).isEmpty()) {
            entity.discard();
            return 0;
        }
        SOAK_ENTITIES.put(player.getUUID(), entity);
        PlaytestProbe.SOAK_ENTITY_SPAWNS.incrementAndGet();
        return 1;
    }

    private static int despawnSoakProjection(ServerPlayer player) {
        var entity = SOAK_ENTITIES.remove(player.getUUID());
        if (entity == null) {
            return 0;
        }
        entity.discard();
        PlaytestProbe.SOAK_ENTITY_DESPAWNS.incrementAndGet();
        return 1;
    }

    private static void join(ServerPlayer player) {
        int joins = PlaytestProbe.JOIN_COUNT.incrementAndGet();
        if ("long".equals(System.getProperty("polymc-reborn.playtest.soakMode", "none"))) {
            // The local, disposable driver must issue hundreds of fixture-only commands without
            // tripping vanilla chat-spam protection. This does not alter production permissions.
            player.level().getServer().getPlayerList().op(new NameAndId(player.getGameProfile()));
        }
        player.setGameMode(GameType.SURVIVAL);
        // Keep simultaneous clients on distinct sight lines to the projected
        // entity. Spawning them at the same coordinates makes the other real
        // player an ambiguous vanilla pick target and does not test the
        // surrogate interaction guard deterministically.
        double spawnX = player.getName().getString().endsWith("MultiB") ? 2.5D : 0.5D;
        player.teleportTo(spawnX, 100.0D, -4.5D);
        player.forceSetRotation(0.0F, true, 0.0F, true);
        player.getFoodData().setFoodLevel(10);
        player.getFoodData().setSaturation(0.0F);
        var inventory = player.getInventory();
        if (INITIALIZED_PLAYERS.add(player.getUUID())) {
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
            inventory.setItem(6, namedStack(FixtureContent.PROPERTY_GUI_ITEM, 1,
                    "PolyMc Reborn Property Furnace", "Explicit server-authoritative property GUI"));
            String externalMode = System.getProperty("polymc-reborn.playtest.externalMode", "none");
            if (!"none".equals(externalMode)) {
                var externalId = net.minecraft.resources.Identifier.parse(requiredProperty(
                        "polymc-reborn.playtest.externalItemId"));
                var externalItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(externalId);
                if (externalItem == Items.AIR) {
                    throw new IllegalStateException("External matrix item is not registered: " + externalId);
                }
                int slot = 7;
                inventory.setItem(slot, namedStack(externalItem, 1,
                        "External Matrix " + externalId,
                        "Real third-party content; server-only Mod installation"));
            }
            inventory.setSelectedSlot(0);
        }
        if (resourcePackUrl == null || resourcePackSha1 == null || resourcePackSha256 == null) {
            throw new IllegalStateException("Playtest client joined before resource pack host was ready");
        }
        var policy = PolyMcReborn.runtime().config().resourcePackPolicy();
        // Resource-pack IDs identify individual protocol pushes, not the immutable ZIP content.
        // Use a distinct deterministic ID per connection so a reconnect cannot deduplicate the
        // second push after Minecraft has removed the first connection's active server packs.
        var pushId = UUID.nameUUIDFromBytes(("polymc-reborn-playtest:" + resourcePackSha256
                + ":connection:" + joins).getBytes(StandardCharsets.UTF_8));
        PolyMcReborn.runtime().playerPackStates().offered(player.getUUID(), pushId);
        if (policy == ResourcePackPolicy.DISABLED) {
            player.sendSystemMessage(Component.literal(
                    "PolyMc Reborn resource pack disabled; safe vanilla carriers are active"));
            return;
        }
        var packPacket = new ClientboundResourcePackPushPacket(pushId, resourcePackUrl,
                resourcePackSha1, policy == ResourcePackPolicy.REQUIRED,
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
        PlaytestProbe.SERVER_TICKS.incrementAndGet();
        FixtureContent.tickPropertyMenus();
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
        String externalMode = System.getProperty("polymc-reborn.playtest.externalMode", "none");
        if ("block".equals(externalMode)) {
            var blockId = net.minecraft.resources.Identifier.parse(requiredProperty(
                    "polymc-reborn.playtest.externalBlockId"));
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(blockId);
            var externalState = server.overworld().getBlockState(EXTERNAL_TARGET);
            if (externalState.is(block)) {
                externalTargetWasPlaced = true;
                externalBlockPlaced = true;
            } else if (externalTargetWasPlaced && externalState.isAir()) {
                externalBlockBroken = true;
            }
        } else if ("armor".equals(externalMode)) {
            var itemId = net.minecraft.resources.Identifier.parse(requiredProperty(
                    "polymc-reborn.playtest.externalItemId"));
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(itemId);
            externalItemEquipped |= server.getPlayerList().getPlayers().stream()
                    .anyMatch(player -> player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(item));
        } else if ("food".equals(externalMode)) {
            var itemId = net.minecraft.resources.Identifier.parse(requiredProperty(
                    "polymc-reborn.playtest.externalItemId"));
            var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(itemId);
            var players = server.getPlayerList().getPlayers();
            externalFoodConsumed |= !players.isEmpty() && players.stream().allMatch(player ->
                    java.util.stream.IntStream.range(0, player.getInventory().getContainerSize())
                            .noneMatch(slot -> player.getInventory().getItem(slot).is(item)));
        }
        server.getPlayerList().getPlayers().forEach(PlaytestFixtureEntrypoint::observeInventory);
        if (Files.exists(stopFile)) {
            PolyMcReborn.runtime().shutdownInteractiveState();
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
            int basicItemRemaining = PlaytestProbe.BASIC_ITEM_REMAINING.get();
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
            boolean apiConsumerLoaded = FabricLoader.getInstance().isModLoaded(
                    "polymc-reborn-api-consumer");
            long projectedInteractions = PolyMcReborn.runtime().entityProjectionBackend()
                    .acceptedInteractionCount();
            long entityPassengerPackets = PolyMcReborn.runtime().entityProjectionBackend()
                    .passengerPacketCount();
            long entityEquipmentPackets = PolyMcReborn.runtime().entityProjectionBackend()
                    .equipmentPacketCount();
            var packPolicy = PolyMcReborn.runtime().config().resourcePackPolicy();
            var packStats = PolyMcReborn.runtime().playerPackStates().stats();
            String playtestMode = System.getProperty("polymc-reborn.playtest.mode", "single");
            String soakMode = System.getProperty("polymc-reborn.playtest.soakMode", "none");
            boolean longSoak = "long".equals(soakMode);
            boolean multiClient = "multi".equals(playtestMode);
            int expectedPackPushes = packPolicy == ResourcePackPolicy.DISABLED ? 0 : multiClient ? 3
                    : playtestMode.startsWith("pack-") ? 1 : longSoak ? 3 : 2;
            SupportAudit supportAudit = auditSupportBundle();
            String externalMode = System.getProperty("polymc-reborn.playtest.externalMode", "none");
            String externalModId = System.getProperty("polymc-reborn.playtest.externalModId", "none");
            boolean externalLoaded = "none".equals(externalMode)
                    || FabricLoader.getInstance().isModLoaded(externalModId);
            boolean externalContentPassed = switch (externalMode) {
                case "armor" -> externalItemEquipped;
                case "block" -> externalBlockPlaced && externalBlockBroken;
                case "food" -> externalFoodConsumed;
                case "none" -> true;
                default -> false;
            };
            int expectedToolDamage = "block".equals(externalMode) ? 3 : 2;
            int expectedSingleConnections = longSoak ? 3 : 2;
            int expectedGuiCycles = longSoak ? 25 : 0;
            int expectedGuiOpens = 3 + expectedGuiCycles;
            int expectedAdminCommands = COMMANDS.size() + (longSoak ? 4 : 0);
            boolean singlePassed = PlaytestProbe.JOIN_COUNT.get() == expectedSingleConnections
                    && PlaytestProbe.DISCONNECT_COUNT.get() == expectedSingleConnections
                    && PlaytestProbe.placedBlockObserved
                    && PlaytestProbe.brokenBlockObserved
                    && PlaytestProbe.simpleBlockPlacedObserved
                    && PlaytestProbe.simpleBlockBrokenObserved
                    && PlaytestProbe.stateToggleObserved
                    && PlaytestProbe.GUI_OPEN_COUNT.get() == expectedGuiOpens
                    && PlaytestProbe.GUI_CLOSE_COUNT.get() == expectedGuiOpens
                    && PlaytestProbe.guiInventoryIntegrity
                    && PlaytestProbe.PROPERTY_GUI_OPEN_COUNT.get() == 2
                    && PlaytestProbe.PROPERTY_TICK_COUNT.get() >= 100
                    && PlaytestProbe.PROPERTY_COMPLETION_COUNT.get() == 1
                    && PlaytestProbe.ENTITY_USE_COUNT.get() == 1
                    && PlaytestProbe.ENTITY_ATTACK_COUNT.get() == 1
                    && projectedInteractions == 2
                    && entityPassengerPackets >= 2
                    && entityEquipmentPackets >= 2
                    && PlaytestProbe.semanticUseObserved
                    && PlaytestProbe.itemDropObserved
                    && PlaytestProbe.itemPickupObserved
                    && basicItemRemaining == 1
                    && foodRemaining == 3
                    && toolDamage == expectedToolDamage
                    && PlaytestProbe.COMMAND_COUNT.get() == expectedAdminCommands
                    && PlaytestProbe.RESOURCE_PACK_PUSH_COUNT.get() == expectedPackPushes
                    && PlaytestProbe.RESOURCE_PACK_REQUEST_COUNT.get() == expectedPackPushes
                    && guiSessions == 0
                    && entitySessions == 0
                    && packStats.activePlayers() == 0
                    && apiConsumerLoaded
                    && supportAudit.valid()
                    && externalLoaded
                    && externalContentPassed
                    && productionJarValid
                    && mappingStable
                    && packStable
                    && (!longSoak || (PlaytestProbe.SOAK_GUI_CYCLES.get() == 25
                    && PlaytestProbe.SOAK_REJECTED_TRANSACTIONS.get() == 25
                    && PlaytestProbe.SOAK_ENTITY_SPAWNS.get() == 50
                    && PlaytestProbe.SOAK_ENTITY_DESPAWNS.get() == 50
                    && PlaytestProbe.SOAK_TRACKING_CYCLES.get() == 10
                    && PlaytestProbe.DIMENSION_CHANGE_COUNT.get() == 20
                    && PlaytestProbe.SUPPORT_BUNDLE_GENERATIONS.get() == 3
                    && PlaytestProbe.MAPPING_DRY_RUNS.get() == 3));
            boolean multiPassed = PlaytestProbe.JOIN_COUNT.get() == 3
                    && PlaytestProbe.DISCONNECT_COUNT.get() == 3
                    && PlaytestProbe.GUI_OPEN_COUNT.get() == 2
                    && PlaytestProbe.GUI_CLOSE_COUNT.get() == 2
                    && PlaytestProbe.guiInventoryIntegrity
                    && FixtureContent.fixtureContainerCount() == 2
                    && PlaytestProbe.ENTITY_USE_COUNT.get() == 1
                    && PlaytestProbe.ENTITY_ATTACK_COUNT.get() == 1
                    && projectedInteractions == 2
                    && entityPassengerPackets >= 3
                    && entityEquipmentPackets >= 3
                    && PlaytestProbe.COMMAND_COUNT.get() == COMMANDS.size()
                    && PlaytestProbe.RESOURCE_PACK_PUSH_COUNT.get() == 3
                    && PlaytestProbe.RESOURCE_PACK_REQUEST_COUNT.get() == 1
                    && packPolicy == ResourcePackPolicy.OPTIONAL
                    && packStats.applied() == 1
                    && packStats.declined() == 2
                    && packStats.failed() == 0
                    && guiSessions == 0
                    && entitySessions == 0
                    && packStats.activePlayers() == 0
                    && apiConsumerLoaded
                    && supportAudit.valid()
                    && productionJarValid
                    && mappingStable
                    && packStable;
            boolean requiredDeclinePassed = "pack-required-decline".equals(playtestMode)
                    && packPolicy == ResourcePackPolicy.REQUIRED
                    && PlaytestProbe.JOIN_COUNT.get() == 1
                    && PlaytestProbe.DISCONNECT_COUNT.get() == 1
                    && PlaytestProbe.RESOURCE_PACK_PUSH_COUNT.get() == 1
                    && PlaytestProbe.RESOURCE_PACK_REQUEST_COUNT.get() == 0
                    && packStats.applied() == 0 && packStats.declined() == 1
                    && packStats.requiredRejected() == 1 && packStats.failed() == 0
                    && PlaytestProbe.COMMAND_COUNT.get() == COMMANDS.size()
                    && apiConsumerLoaded && supportAudit.valid() && productionJarValid
                    && mappingStable && packStable;
            boolean disabledPackPassed = "pack-disabled".equals(playtestMode)
                    && packPolicy == ResourcePackPolicy.DISABLED
                    && PlaytestProbe.JOIN_COUNT.get() == 1
                    && PlaytestProbe.DISCONNECT_COUNT.get() == 1
                    && PlaytestProbe.RESOURCE_PACK_PUSH_COUNT.get() == 0
                    && PlaytestProbe.RESOURCE_PACK_REQUEST_COUNT.get() == 0
                    && packStats.applied() == 0 && packStats.declined() == 0 && packStats.failed() == 0
                    && PlaytestProbe.COMMAND_COUNT.get() == COMMANDS.size()
                    && apiConsumerLoaded && supportAudit.valid() && productionJarValid
                    && mappingStable && packStable;
            boolean passed = multiClient ? multiPassed
                    : "pack-required-decline".equals(playtestMode) ? requiredDeclinePassed
                    : "pack-disabled".equals(playtestMode) ? disabledPackPassed : singlePassed;
            String json = "{\n"
                    + "  \"schema_version\": 1,\n"
                    + "  \"result\": \"" + (passed ? "passed" : "failed") + "\",\n"
                    + "  \"playtest_mode\": \"" + jsonEscape(playtestMode) + "\",\n"
                    + "  \"soak_mode\": \"" + jsonEscape(soakMode) + "\",\n"
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
                    + "  \"gui_container_count\": " + FixtureContent.fixtureContainerCount() + ",\n"
                    + "  \"property_gui_open_count\": "
                    + PlaytestProbe.PROPERTY_GUI_OPEN_COUNT.get() + ",\n"
                    + "  \"property_tick_count\": " + PlaytestProbe.PROPERTY_TICK_COUNT.get() + ",\n"
                    + "  \"property_completion_count\": "
                    + PlaytestProbe.PROPERTY_COMPLETION_COUNT.get() + ",\n"
                    + "  \"entity_use_count\": " + PlaytestProbe.ENTITY_USE_COUNT.get() + ",\n"
                    + "  \"entity_attack_count\": " + PlaytestProbe.ENTITY_ATTACK_COUNT.get() + ",\n"
                    + "  \"dimension_change_count\": "
                    + PlaytestProbe.DIMENSION_CHANGE_COUNT.get() + ",\n"
                    + "  \"entity_interaction_callbacks\": " + projectedInteractions + ",\n"
                    + "  \"entity_passenger_packets\": " + entityPassengerPackets + ",\n"
                    + "  \"entity_equipment_packets\": " + entityEquipmentPackets + ",\n"
                    + "  \"semantic_use_observed\": " + PlaytestProbe.semanticUseObserved + ",\n"
                    + "  \"item_drop_observed\": " + PlaytestProbe.itemDropObserved + ",\n"
                    + "  \"item_pickup_observed\": " + PlaytestProbe.itemPickupObserved + ",\n"
                    + "  \"basic_item_remaining\": " + basicItemRemaining + ",\n"
                    + "  \"tool_damage\": " + toolDamage + ",\n"
                    + "  \"food_remaining\": " + foodRemaining + ",\n"
                    + "  \"client_profile\": \"VANILLA\",\n"
                    + "  \"resource_pack_policy\": \"" + packPolicy + "\",\n"
                    + "  \"resource_pack_applied_count\": " + packStats.applied() + ",\n"
                    + "  \"resource_pack_declined_count\": " + packStats.declined() + ",\n"
                    + "  \"resource_pack_required_rejected_count\": "
                    + packStats.requiredRejected() + ",\n"
                    + "  \"resource_pack_failed_count\": " + packStats.failed() + ",\n"
                    + "  \"admin_command_count\": " + PlaytestProbe.COMMAND_COUNT.get() + ",\n"
                    + "  \"mapping_dry_run_count\": " + PlaytestProbe.MAPPING_DRY_RUNS.get() + ",\n"
                    + "  \"support_bundle_generation_count\": "
                    + PlaytestProbe.SUPPORT_BUNDLE_GENERATIONS.get() + ",\n"
                    + "  \"soak_gui_cycles\": " + PlaytestProbe.SOAK_GUI_CYCLES.get() + ",\n"
                    + "  \"soak_rejected_transactions\": "
                    + PlaytestProbe.SOAK_REJECTED_TRANSACTIONS.get() + ",\n"
                    + "  \"soak_entity_spawns\": " + PlaytestProbe.SOAK_ENTITY_SPAWNS.get() + ",\n"
                    + "  \"soak_entity_despawns\": " + PlaytestProbe.SOAK_ENTITY_DESPAWNS.get() + ",\n"
                    + "  \"soak_tracking_cycles\": " + PlaytestProbe.SOAK_TRACKING_CYCLES.get() + ",\n"
                    + "  \"server_ticks\": " + PlaytestProbe.SERVER_TICKS.get() + ",\n"
                    + "  \"transaction_average_millis\": " + transactionAverageMillis() + ",\n"
                    + "  \"transaction_max_millis\": " + transactionMaxMillis() + ",\n"
                    + "  \"jvm_heap_used_bytes\": " + heapUsedBytes() + ",\n"
                    + "  \"jvm_rss_bytes\": " + linuxRssBytes() + ",\n"
                    + "  \"jvm_thread_count\": " + ManagementFactory.getThreadMXBean().getThreadCount() + ",\n"
                    + "  \"jvm_open_file_count\": " + linuxOpenFileCount() + ",\n"
                    + "  \"jvm_gc_count\": " + gcCount() + ",\n"
                    + "  \"mapping_cache_size\": " + PolyMcReborn.runtime().plan().size() + ",\n"
                    + "  \"resource_pack_push_count\": "
                    + PlaytestProbe.RESOURCE_PACK_PUSH_COUNT.get() + ",\n"
                    + "  \"resource_pack_request_count\": "
                    + PlaytestProbe.RESOURCE_PACK_REQUEST_COUNT.get() + ",\n"
                    + "  \"gui_active_sessions\": " + guiSessions + ",\n"
                    + "  \"entity_projection_sessions\": " + entitySessions + ",\n"
                    + "  \"active_interaction_proxies\": " + entitySessions + ",\n"
                    + "  \"resource_pack_active_sessions\": " + packStats.activePlayers() + ",\n"
                    + "  \"api_consumer_loaded\": " + apiConsumerLoaded + ",\n"
                    + "  \"support_bundle_valid\": " + supportAudit.valid() + ",\n"
                    + "  \"support_bundle_sha256\": \"" + supportAudit.sha256() + "\",\n"
                    + "  \"support_bundle_entries\": " + supportAudit.entries() + ",\n"
                    + "  \"external_mode\": \"" + jsonEscape(externalMode) + "\",\n"
                    + "  \"external_mod_id\": \"" + jsonEscape(externalModId) + "\",\n"
                    + "  \"external_mod_loaded\": " + externalLoaded + ",\n"
                    + "  \"external_content_passed\": " + externalContentPassed + ",\n"
                    + "  \"external_item_equipped\": " + externalItemEquipped + ",\n"
                    + "  \"external_food_consumed\": " + externalFoodConsumed + ",\n"
                    + "  \"external_block_placed\": " + externalBlockPlaced + ",\n"
                    + "  \"external_block_broken\": " + externalBlockBroken + ",\n"
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

    private static double transactionAverageMillis() {
        int count = PlaytestProbe.SOAK_REJECTED_TRANSACTIONS.get();
        return count == 0 ? 0.0D
                : PlaytestProbe.REJECTED_TRANSACTION_TOTAL_NANOS.get() / 1_000_000.0D / count;
    }

    private static double transactionMaxMillis() {
        return PlaytestProbe.REJECTED_TRANSACTION_MAX_NANOS.get() / 1_000_000.0D;
    }

    private static long heapUsedBytes() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0L, bean.getCollectionCount())).sum();
    }

    private static long linuxRssBytes() {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) {
            return -1L;
        }
        try {
            for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                if (line.startsWith("VmRSS:")) {
                    String digits = line.substring("VmRSS:".length()).trim().split("\\s+")[0];
                    return Long.parseLong(digits) * 1024L;
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            return -1L;
        }
        return -1L;
    }

    private static long linuxOpenFileCount() {
        Path descriptors = Path.of("/proc/self/fd");
        if (!Files.isDirectory(descriptors)) {
            return -1L;
        }
        try (var entries = Files.list(descriptors)) {
            return entries.count();
        } catch (IOException ignored) {
            return -1L;
        }
    }

    private static void observeInventory(ServerPlayer player) {
        int damage = toolDamage(player);
        if (damage >= 0) {
            PlaytestProbe.MAX_TOOL_DAMAGE.accumulateAndGet(damage, Math::max);
        }
        int food = 0;
        int basicItems = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            var stack = player.getInventory().getItem(slot);
            if (stack.is(FixtureContent.BASIC_ITEM)) {
                basicItems += stack.getCount();
            }
            if (stack.is(FixtureContent.FOOD_ITEM)) {
                food += stack.getCount();
            }
        }
        int previousBasicItems = PlaytestProbe.BASIC_ITEM_REMAINING.getAndSet(basicItems);
        if (previousBasicItems > 0 && basicItems == 0) {
            PlaytestProbe.itemDropObserved = true;
        } else if (PlaytestProbe.itemDropObserved && previousBasicItems == 0 && basicItems > 0) {
            PlaytestProbe.itemPickupObserved = true;
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

    private static SupportAudit auditSupportBundle() {
        Path bundle = FabricLoader.getInstance().getConfigDir().resolve("polymc-reborn")
                .resolve("support").resolve("polymc-reborn-support.zip");
        if (!Files.isRegularFile(bundle, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return new SupportAudit(false, "missing", 0);
        }
        int entries = 0;
        try (var zip = new java.util.zip.ZipFile(bundle.toFile(), StandardCharsets.UTF_8)) {
            var enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                var entry = enumeration.nextElement();
                entries++;
                String name = entry.getName();
                if (entry.isDirectory() || name.contains("..") || name.endsWith(".jar")
                        || name.toLowerCase(java.util.Locale.ROOT).contains("world")) {
                    return new SupportAudit(false, "unsafe-entry", entries);
                }
                String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                String lower = text.toLowerCase(java.util.Locale.ROOT);
                if (text.matches("(?s).*[A-Za-z]:\\\\.*") || lower.contains("github_token=")
                        || lower.contains("authorization: bearer") || lower.contains("hmac_key=")) {
                    return new SupportAudit(false, "sensitive-content", entries);
                }
            }
            return new SupportAudit(entries >= 5, digest("SHA-256", Files.readAllBytes(bundle)), entries);
        } catch (IOException exception) {
            return new SupportAudit(false, "io-error", entries);
        }
    }

    private record SupportAudit(boolean valid, String sha256, int entries) {
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
        var expected = new java.util.ArrayList<>(List.of(
                new Expected(ContentType.ITEM, FixtureContent.MOD_ID + ":food_item"),
                new Expected(ContentType.BLOCK, FixtureContent.MOD_ID + ":full_block"),
                new Expected(ContentType.BLOCK, FixtureContent.MOD_ID + ":stateful_block"),
                new Expected(ContentType.GUI, FixtureContent.MOD_ID + ":fixture_menu"),
                new Expected(ContentType.GUI, FixtureContent.MOD_ID + ":property_menu"),
                new Expected(ContentType.ENTITY, FixtureContent.MOD_ID + ":fixture_entity"),
                new Expected(ContentType.ITEM, "polymc-reborn-api-consumer:consumer_item"),
                new Expected(ContentType.BLOCK, "polymc-reborn-api-consumer:consumer_block"),
                new Expected(ContentType.GUI, "polymc-reborn-api-consumer:consumer_menu"),
                new Expected(ContentType.ENTITY, "polymc-reborn-api-consumer:consumer_entity")));
        String externalMode = System.getProperty("polymc-reborn.playtest.externalMode", "none");
        if (!"none".equals(externalMode)) {
            String itemId = requiredProperty("polymc-reborn.playtest.externalItemId");
            expected.add(new Expected(ContentType.ITEM, itemId));
            if ("block".equals(externalMode)) {
                expected.add(new Expected(ContentType.BLOCK,
                        requiredProperty("polymc-reborn.playtest.externalBlockId")));
            }
        }
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
