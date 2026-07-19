/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.playtest.client;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.FurnaceMenu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Real Minecraft client driver connecting to an independently launched production server. */
public final class RebornClientPlaytest implements FabricClientGameTest {
    private static final Set<String> ALLOWED_MODS = Set.of(
            "minecraft", "java", "fabricloader", "mixinextras", "fabric-api-base",
            "fabric-resource-loader-v1", "fabric-client-gametest-api-v1",
            "polymc-reborn-client-driver");
    private static final Identifier PACK_PROBE = Identifier.fromNamespaceAndPath(
            "polymc-reborn-gametest", "items/basic_item.json");
    private static final Identifier UPGRADE_PACK_PROBE = Identifier.fromNamespaceAndPath(
            "polymc-reborn-upgrade-fixture", "items/stable_item.json");
    private static final BlockPos PLACE_SUPPORT = new BlockPos(0, 100, 0);
    private static final BlockPos PLACED_BLOCK = new BlockPos(0, 100, -1);
    private static final BlockPos SIMPLE_SUPPORT = new BlockPos(1, 100, 0);
    private static final BlockPos SIMPLE_BLOCK = new BlockPos(1, 100, -1);
    private static final BlockPos EXTERNAL_SUPPORT = new BlockPos(2, 100, 0);
    private static final BlockPos EXTERNAL_BLOCK = new BlockPos(2, 100, -1);

    private final List<Step> steps = new ArrayList<>();
    private Path reportDirectory;
    private Path screenshotsDirectory;
    private String endpoint;
    private String expectedResourcePackSha256;
    private String expectedResourcePackSha1;
    private String measuredResourcePackSha256 = "missing";
    private String measuredResourcePackSha1 = "missing";
    private long measuredResourcePackBytes;
    private String inventoryBeforeReconnect;
    private String externalMode;
    private String clientId;
    private String scenario;
    private Path coordinatorDirectory;
    private Instant lastStepBoundary = Instant.now();

    @Override
    public void runTest(ClientGameTestContext context) {
        endpoint = requiredProperty("polymc-reborn.playtest.address");
        expectedResourcePackSha256 = requiredProperty("polymc-reborn.playtest.packSha256")
                .toLowerCase(java.util.Locale.ROOT);
        expectedResourcePackSha1 = requiredProperty("polymc-reborn.playtest.packSha1")
                .toLowerCase(java.util.Locale.ROOT);
        externalMode = System.getProperty("polymc-reborn.playtest.externalMode", "none");
        clientId = System.getProperty("polymc-reborn.playtest.clientId", "single");
        scenario = System.getProperty("polymc-reborn.playtest.scenario", "single");
        reportDirectory = Path.of(requiredProperty("polymc-reborn.playtest.reportDir"))
                .toAbsolutePath().normalize();
        screenshotsDirectory = reportDirectory.resolve("screenshots");
        Throwable failure = null;
        try {
            Files.createDirectories(screenshotsDirectory);
            verifyClientIsolation();
            pass("client-isolation", "Only the driver and its minimal Fabric client test dependencies are loaded");

            if (scenario.startsWith("pack-")) {
                runPackPolicyScenario(context);
                return;
            }
            if (scenario.startsWith("multi-")) {
                coordinatorDirectory = Path.of(requiredProperty("polymc-reborn.playtest.coordinatorDir"))
                        .toAbsolutePath().normalize();
                Files.createDirectories(coordinatorDirectory);
                runMultiClientScenario(context);
                return;
            }
            if (scenario.startsWith("upgrade-")) {
                runUpgradeScenario(context);
                return;
            }

            connect(context);
            screenshot(context, "01-connected");
            acceptAndWaitForPack(context, "initial connection", true);
            exerciseMovementAndHotbar(context);
            exerciseSemanticUse(context);
            exerciseDropAndPickup(context);
            exercisePlaceAndBreak(context);
            exerciseGui(context);
            exercisePropertyGui(context);
            exerciseEntity(context);
            exerciseExternalContent(context);

            inventoryBeforeReconnect = context.computeOnClient(RebornClientPlaytest::inventoryFingerprint);
            openProjectedGui(context);
            pass("gui-open-at-disconnect", "A projected GUI remained open when the network session was closed");
            disconnect(context);
            pass("disconnect", "Client closed a live GUI session and returned to TitleScreen after a normal disconnect");
            connect(context);
            acceptAndWaitForPack(context, "reconnection", false);
            context.waitFor(client -> clientSurrogate(client) != null, 200);
            String inventoryAfterReconnect = context.computeOnClient(RebornClientPlaytest::inventoryFingerprint);
            if (!inventoryBeforeReconnect.equals(inventoryAfterReconnect)) {
                throw new AssertionError("Player inventory changed across reconnect");
            }
            screenshot(context, "17-reconnected");
            pass("reconnect", "A new network session joined, reapplied the same resource pack and preserved inventory");
        } catch (Throwable throwable) {
            failure = throwable;
            fail("playtest", throwable.getClass().getSimpleName() + ": " + safeMessage(throwable));
        } finally {
            try {
                ensureDisconnected(context);
            } catch (Throwable cleanupFailure) {
                fail("cleanup", cleanupFailure.getClass().getSimpleName() + ": " + safeMessage(cleanupFailure));
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            writeReports(failure == null);
        }
        if (failure != null) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Client playtest failed", failure);
        }
    }

    private void runUpgradeScenario(ClientGameTestContext context) {
        connect(context);
        screenshot(context, "01-upgrade-connected");
        acceptAndWaitForPack(context, "upgrade connection", true);
        context.waitFor(client -> client.player != null
                && !client.player.getInventory().getItem(0).isEmpty(), 400);
        String inventory = context.computeOnClient(RebornClientPlaytest::inventoryFingerprint);
        screenshot(context, "04-upgrade-persisted-item");
        pass("upgrade-player-inventory",
                "The isolated client received a non-empty persisted hotbar stack: " + inventory);
        disconnect(context);
        pass("upgrade-disconnect", "Upgrade client disconnected normally after applying the server pack");
    }

    private void runPackPolicyScenario(ClientGameTestContext context) {
        connect(context);
        if ("pack-required-decline".equals(scenario)) {
            context.waitFor(client -> client.screen instanceof ConfirmScreen, 1200);
            screenshot(context, "01-required-pack-prompt");
            clickPackDecline(context);
            context.waitFor(client -> client.level == null, 1200);
            context.runOnClient(client -> client.setScreen(new TitleScreen()));
            context.waitFor(client -> client.screen instanceof TitleScreen, 200);
            pass("required-pack-decline",
                    "Declining a REQUIRED pack caused the vanilla protocol to end the connection");
            return;
        }
        if ("pack-disabled".equals(scenario)) {
            context.waitTicks(80);
            context.computeOnClient(client -> {
                if (client.screen instanceof ConfirmScreen
                        || client.getResourceManager().getResource(PACK_PROBE).isPresent()) {
                    throw new AssertionError("DISABLED policy unexpectedly offered or applied a resource pack");
                }
                return true;
            });
            assertSafeCarrierWithoutPack(context);
            screenshot(context, "01-disabled-safe-carrier");
            disconnect(context);
            pass("disabled-pack-safe-carrier",
                    "DISABLED policy sent no pack and retained a vanilla-safe carrier");
            return;
        }
        throw new IllegalArgumentException("Unknown pack-policy scenario " + scenario);
    }

    private void runMultiClientScenario(ClientGameTestContext context) {
        boolean clientA = "multi-a".equals(scenario);
        if (!clientA && !"multi-b".equals(scenario)) {
            throw new IllegalArgumentException("Unknown multi-client scenario " + scenario);
        }
        String role = clientA ? "a" : "b";
        String other = clientA ? "b" : "a";
        connect(context);
        if (clientA) {
            acceptAndWaitForPack(context, "multi-client-a", true);
        } else {
            declineOptionalPack(context);
            assertSafeCarrierWithoutPack(context);
        }
        screenshot(context, "01-multi-online-" + role);
        writeMarker("online-" + role, inventoryFingerprint(context));
        // Client processes initialize sequentially to avoid two simultaneous LWJGL/DataFixer cold starts
        // exhausting a constrained CI renderer. Client A stays live while B starts, so every scenario below
        // still executes with both independent network sessions online.
        waitMarker(context, "online-" + other, 4000);
        concurrentProjectedGui(context, role, other);
        screenshot(context, "02-multi-gui-" + role);

        context.waitFor(client -> clientSurrogate(client) != null, 200);
        aimAtProjectedEntity(context);
        context.getInput().pressKey(options -> clientA ? options.keyUse : options.keyAttack);
        context.waitTicks(6);
        pass(clientA ? "multi-entity-use" : "multi-entity-attack",
                "Client " + role.toUpperCase(java.util.Locale.ROOT)
                        + " sent an independent guarded interaction to the live surrogate");
        screenshot(context, "03-multi-entity-" + role);
        writeMarker("entity-" + role, "completed");
        waitMarker(context, "entity-" + other, 1600);
        exerciseMultiDimensionIsolation(context, clientA);

        if (clientA) {
            disconnect(context);
            writeMarker("disconnected-a", "normal-disconnect");
            waitMarker(context, "survived-b", 1200);
            connect(context);
            declineOptionalPack(context);
            context.waitFor(client -> client.level != null && java.util.stream.StreamSupport.stream(
                            client.level.entitiesForRendering().spliterator(), false)
                    .filter(entity -> entity.getType() == EntityType.ARMOR_STAND
                            && entity.getCustomName() != null
                            && entity.getCustomName().getString().contains("Projected Fixture Entity"))
                    .count() == 1L, 400);
            writeMarker("reconnected-a", inventoryFingerprint(context));
            pass("multi-reconnect-a",
                    "Client A reconnected after Client B survived and saw exactly one projected surrogate");
            screenshot(context, "04-multi-reconnected-a");
            disconnect(context);
        } else {
            waitMarker(context, "disconnected-a", 1200);
            context.computeOnClient(client -> {
                if (client.level == null || client.player == null || client.getConnection() == null) {
                    throw new AssertionError("Client B lost its independent connection when Client A disconnected");
                }
                return true;
            });
            writeMarker("survived-b", inventoryFingerprint(context));
            pass("multi-disconnect-isolation",
                    "Client B remained connected with its own inventory after Client A disconnected");
            screenshot(context, "04-multi-survived-b");
            waitMarker(context, "reconnected-a", 1200);
            disconnect(context);
        }
        pass("multi-client-complete", "Independent client role " + role + " completed cleanly");
    }

    private void exerciseMultiDimensionIsolation(ClientGameTestContext context, boolean clientA) {
        if (clientA) {
            context.runOnClient(client -> client.player.connection.sendCommand(
                    "polymcreborn-playtest dimension-cycle"));
            context.waitFor(client -> client.level != null && client.level.dimension() == Level.NETHER,
                    1200);
            context.computeOnClient(client -> {
                long staleSurrogates = java.util.stream.StreamSupport.stream(
                                client.level.entitiesForRendering().spliterator(), false)
                        .filter(entity -> entity.getType() == EntityType.ARMOR_STAND
                                && entity.getCustomName() != null
                                && entity.getCustomName().getString().contains("Projected Fixture Entity"))
                        .count();
                if (staleSurrogates != 0L) {
                    throw new AssertionError("Old-dimension surrogate remained after dimension transfer");
                }
                return true;
            });
            writeMarker("dimension-left-a", "minecraft:the_nether");
            screenshot(context, "04-multi-dimension-a");
            waitMarker(context, "dimension-survived-b", 1600);
            context.runOnClient(client -> client.player.connection.sendCommand(
                    "polymcreborn-playtest dimension-cycle"));
            context.waitFor(client -> client.level != null && client.level.dimension() == Level.OVERWORLD
                    && java.util.stream.StreamSupport.stream(
                                    client.level.entitiesForRendering().spliterator(), false)
                            .filter(entity -> entity.getType() == EntityType.ARMOR_STAND
                                    && entity.getCustomName() != null
                                    && entity.getCustomName().getString().contains("Projected Fixture Entity"))
                            .count() == 1L, 1200);
            writeMarker("dimension-returned-a", "minecraft:overworld");
            pass("multi-dimension-a",
                    "Client A left and returned while its old projection was removed and rebuilt once");
        } else {
            waitMarker(context, "dimension-left-a", 1600);
            context.computeOnClient(client -> {
                long surrogates = java.util.stream.StreamSupport.stream(
                                client.level.entitiesForRendering().spliterator(), false)
                        .filter(entity -> entity.getType() == EntityType.ARMOR_STAND
                                && entity.getCustomName() != null
                                && entity.getCustomName().getString().contains("Projected Fixture Entity"))
                        .count();
                if (client.level.dimension() != Level.OVERWORLD || surrogates != 1L) {
                    throw new AssertionError("Client B tracking changed when Client A left the dimension");
                }
                return true;
            });
            writeMarker("dimension-survived-b", "minecraft:overworld");
            waitMarker(context, "dimension-returned-a", 1600);
            pass("multi-dimension-b",
                    "Client B remained in the overworld and continued tracking exactly one surrogate");
        }
    }

    private void declineOptionalPack(ClientGameTestContext context) {
        clickPackDecline(context);
        context.waitFor(client -> client.screen == null && client.level != null && client.player != null
                && client.getResourceManager().getResource(PACK_PROBE).isEmpty(), 1200);
        pass("resource-pack-declined", "Declined OPTIONAL pack and remained in the live server session");
    }

    private void clickPackDecline(ClientGameTestContext context) {
        context.waitFor(client -> client.screen instanceof ConfirmScreen, 1200);
        var declineButton = context.computeOnClient(client -> {
            if (!(client.screen instanceof ConfirmScreen confirm)) {
                throw new IllegalStateException("Optional resource-pack prompt disappeared before decline");
            }
            var buttons = confirm.children().stream().filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast).toList();
            if (buttons.size() < 2) {
                throw new IllegalStateException("Optional resource-pack prompt has no decline button");
            }
            var button = buttons.getLast();
            return windowCursor(client, button.getX() + button.getWidth() / 2.0D,
                    button.getY() + button.getHeight() / 2.0D);
        });
        context.getInput().setCursorPos(declineButton[0], declineButton[1]);
        context.getInput().pressMouse(0);
    }

    private void assertSafeCarrierWithoutPack(ClientGameTestContext context) {
        context.computeOnClient(client -> {
            var stack = client.player.getInventory().getItem(0);
            Identifier carrier = BuiltInRegistries.ITEM.getKey(stack.getItem());
            Identifier model = stack.get(DataComponents.ITEM_MODEL);
            if (stack.isEmpty() || !Identifier.DEFAULT_NAMESPACE.equals(carrier.getNamespace())
                    || model != null && !Identifier.DEFAULT_NAMESPACE.equals(model.getNamespace())) {
                throw new AssertionError("Declined-pack client received a custom registry/model identifier");
            }
            return true;
        });
        pass("resource-pack-safe-carrier", "Declined-pack client received only a vanilla carrier/model");
    }

    private void concurrentProjectedGui(ClientGameTestContext context, String role, String other) {
        openProjectedGui(context);
        assertMenuStack(context, 0, Items.DIAMOND, 16);
        assertMenuStack(context, 1, Items.EMERALD, 8);
        writeMarker("gui-open-" + role, projectedMenuFingerprint(context));
        waitMarker(context, "gui-open-" + other, 400);
        int sourceSlot = "a".equals(role) ? 0 : 1;
        int targetSlot = "a".equals(role) ? 2 : 3;
        var source = containerSlot(context, sourceSlot);
        var target = containerSlot(context, targetSlot);
        context.getInput().setCursorPos(source[0], source[1]);
        context.getInput().pressMouse(1);
        context.waitTicks(2);
        context.getInput().setCursorPos(target[0], target[1]);
        context.getInput().pressMouse(0);
        context.waitTicks(2);
        context.computeOnClient(client -> {
            if (client.player.containerMenu.getCarried().isEmpty()
                    && !client.player.containerMenu.slots.get(targetSlot).getItem().isEmpty()) {
                return true;
            }
            throw new AssertionError("Concurrent projected GUI transaction left a ghost or empty target");
        });
        writeMarker("gui-state-" + role, projectedMenuFingerprint(context));
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitFor(client -> !(client.screen instanceof AbstractContainerScreen<?>), 100);
        pass("multi-gui-" + role,
                "Client " + role.toUpperCase(java.util.Locale.ROOT)
                        + " mutated its own authoritative container while both GUI sessions were open");
    }

    private String inventoryFingerprint(ClientGameTestContext context) {
        return context.computeOnClient(RebornClientPlaytest::inventoryFingerprint);
    }

    private String projectedMenuFingerprint(ClientGameTestContext context) {
        return context.computeOnClient(RebornClientPlaytest::projectedMenuFingerprint);
    }

    private void writeMarker(String name, String value) {
        try {
            Files.writeString(coordinatorDirectory.resolve(name + ".marker"), value + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write multi-client coordination marker " + name, exception);
        }
    }

    private void waitMarker(ClientGameTestContext context, String name, int timeoutTicks) {
        Path marker = coordinatorDirectory.resolve(name + ".marker");
        context.waitFor(client -> Files.isRegularFile(marker), timeoutTicks);
    }

    private void verifyClientIsolation() {
        var loaded = new TreeSet<String>();
        FabricLoader.getInstance().getAllMods().forEach(mod -> loaded.add(mod.getMetadata().getId()));
        var unexpected = new TreeSet<>(loaded);
        unexpected.removeAll(ALLOWED_MODS);
        if (!unexpected.isEmpty()) {
            throw new IllegalStateException("Unexpected client mods: " + unexpected);
        }
        for (var forbidden : List.of("polymc-reborn", "polymc-reborn-gametest", "polymc")) {
            if (loaded.contains(forbidden)) {
                throw new IllegalStateException("Forbidden server implementation loaded on client: " + forbidden);
            }
        }
        if (loaded.stream().anyMatch(id -> id.startsWith("polymer-"))) {
            throw new IllegalStateException("Polymer must not be present in the isolated client process: " + loaded);
        }
        try {
            var json = new StringBuilder("{\n  \"schema_version\": 1,\n  \"mods\": [\n");
            var mods = FabricLoader.getInstance().getAllMods().stream()
                    .sorted(java.util.Comparator.comparing(mod -> mod.getMetadata().getId())).toList();
            for (int index = 0; index < mods.size(); index++) {
                var metadata = mods.get(index).getMetadata();
                json.append("    {\"id\": \"").append(escape(metadata.getId()))
                        .append("\", \"version\": \"")
                        .append(escape(metadata.getVersion().getFriendlyString())).append("\"}")
                        .append(index + 1 == mods.size() ? "\n" : ",\n");
            }
            json.append("  ]\n}\n");
            Files.writeString(reportDirectory.resolve("loaded-client-mods.json"), json, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not record the isolated client mod list", exception);
        }
    }

    private void connect(ClientGameTestContext context) {
        context.runOnClient(client -> {
            var address = ServerAddress.parseString(endpoint);
            var data = new ServerData("PolyMc Reborn isolated playtest", endpoint, ServerData.Type.OTHER);
            data.setResourcePackStatus(ServerData.ServerPackStatus.PROMPT);
            ConnectScreen.startConnecting(client.screen == null ? new TitleScreen() : client.screen,
                    client, address, data, false, null);
        });
        context.waitFor(client -> client.level != null && client.player != null, 1200);
        context.waitFor(client -> client.levelRenderer.hasRenderedAllSections(), 1200);
        pass("connect", "Connected to the independently launched server at " + endpoint);
    }

    private void acceptAndWaitForPack(ClientGameTestContext context, String phase, boolean captureEvidence) {
        context.waitFor(client -> client.screen instanceof ConfirmScreen, 1200);
        if (captureEvidence) {
            screenshot(context, "02-resource-pack-prompt");
        }
        context.waitFor(client -> client.screen instanceof ConfirmScreen confirm
                && confirm.children().stream()
                .filter(AbstractButton.class::isInstance)
                .map(AbstractButton.class::cast)
                .findFirst().map(button -> button.active).orElse(false), 100);
        var acceptButton = context.computeOnClient(client -> {
            if (!(client.screen instanceof ConfirmScreen confirm)) {
                throw new IllegalStateException("Resource-pack confirmation screen disappeared before acceptance");
            }
            var button = confirm.children().stream()
                    .filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("Resource-pack prompt has no accept button"));
            return windowCursor(client, button.getX() + button.getWidth() / 2.0D,
                    button.getY() + button.getHeight() / 2.0D);
        });
        context.getInput().setCursorPos(acceptButton[0], acceptButton[1]);
        context.getInput().pressMouse(0);
        context.waitFor(client -> client.getResourceManager().getResource(packProbe()).isPresent(), 1200);
        context.waitFor(client -> client.getOverlay() == null && client.screen == null
                && client.level != null && client.player != null, 1200);
        context.waitTicks(10);
        measureDownloadedResourcePack(captureEvidence ? 1 : 2);
        if (captureEvidence) {
            screenshot(context, "03-resource-pack-applied");
        }
        pass("resource-pack-" + phase.replace(' ', '-'),
                "Accepted the vanilla prompt and the live resource manager exposes the scenario pack probe after "
                        + phase);
    }

    private Identifier packProbe() {
        return scenario.startsWith("upgrade-") ? UPGRADE_PACK_PROBE : PACK_PROBE;
    }

    private void measureDownloadedResourcePack(int expectedCopies) {
        Path downloads = Path.of("downloads").toAbsolutePath().normalize();
        if (!Files.isDirectory(downloads) || Files.isSymbolicLink(downloads)) {
            throw new AssertionError("Minecraft client download directory is missing or unsafe");
        }
        try (var paths = Files.walk(downloads, 4)) {
            var matches = paths.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> {
                        try {
                            long size = Files.size(path);
                            return size > 0 && size <= 128L * 1024L * 1024L;
                        } catch (IOException exception) {
                            return false;
                        }
                    })
                    .map(path -> new DownloadedPack(path, digest(path, "SHA-256"), digest(path, "SHA-1"),
                            fileSize(path)))
                    .filter(pack -> pack.sha256().equals(expectedResourcePackSha256)
                            && pack.sha1().equals(expectedResourcePackSha1))
                    .toList();
            if (matches.size() != expectedCopies) {
                throw new AssertionError("Expected exactly " + expectedCopies
                        + " downloaded resource-pack file(s) matching both server hashes, found "
                        + matches.size());
            }
            var measured = matches.getFirst();
            measuredResourcePackSha256 = measured.sha256();
            measuredResourcePackSha1 = measured.sha1();
            measuredResourcePackBytes = measured.bytes();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not inspect Minecraft's downloaded resource pack", exception);
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read downloaded resource-pack size", exception);
        }
    }

    private static String digest(Path path, String algorithm) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(path)));
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not hash downloaded resource pack with " + algorithm, exception);
        }
    }

    private void exerciseMovementAndHotbar(ClientGameTestContext context) {
        var before = context.computeOnClient(client -> client.player.position());
        context.getInput().lookAt(35.0F, 5.0F);
        context.getInput().holdKeyFor(options -> options.keyUp, 6);
        context.waitTicks(4);
        var after = context.computeOnClient(client -> client.player.position());
        if (after.distanceTo(before) < 0.15D) {
            throw new AssertionError("Synthetic forward input did not move the real client player");
        }
        context.computeOnClient(client -> {
            if (Math.abs(client.player.getYRot() - 35.0F) > 0.1F
                    || Math.abs(client.player.getXRot() - 5.0F) > 0.1F) {
                throw new AssertionError("Synthetic look input did not update client yaw and pitch");
            }
            return true;
        });
        pass("movement-look", "Forward movement and camera rotation changed live client state");

        context.getInput().pressKey(options -> options.keyHotbarSlots[1]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 1, 100);
        pass("hotbar", "Number-key input selected server-provided hotbar slot 2");
    }

    private void exercisePlaceAndBreak(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyHotbarSlots[4]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 4, 100);
        context.getInput().lookAt(SIMPLE_SUPPORT);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(SIMPLE_SUPPORT), 200);
        context.getInput().pressKey(options -> options.keyUse);
        context.waitFor(client -> !client.level.getBlockState(SIMPLE_BLOCK).isAir(), 200);
        assertBlockModelPresent(context,
                context.computeOnClient(client -> client.level.getBlockState(SIMPLE_BLOCK)));
        context.getInput().pressKey(options -> options.keyHotbarSlots[2]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 2, 100);
        int simpleDamageBefore = context.computeOnClient(client ->
                client.player.getInventory().getSelectedItem().getDamageValue());
        context.getInput().lookAt(SIMPLE_BLOCK);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(SIMPLE_BLOCK), 200);
        context.getInput().holdKey(options -> options.keyAttack);
        try {
            context.waitFor(client -> client.level.getBlockState(SIMPLE_BLOCK).isAir(), 600);
        } finally {
            context.getInput().releaseKey(options -> options.keyAttack);
        }
        context.waitFor(client -> client.player.getInventory().getSelectedItem().getDamageValue()
                == simpleDamageBefore + 1, 200);
        pass("simple-block", "Placed and broke a simple full-cube custom block through real client input");

        context.getInput().pressKey(options -> options.keyHotbarSlots[1]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 1, 100);
        context.getInput().lookAt(PLACE_SUPPORT);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(PLACE_SUPPORT), 200);
        context.getInput().pressKey(options -> options.keyUse);
        context.waitFor(client -> !client.level.getBlockState(PLACED_BLOCK).isAir(), 200);
        pass("place", "Use input placed the real custom block through its vanilla client carrier");

        var inactiveCarrier = context.computeOnClient(client -> client.level.getBlockState(PLACED_BLOCK));
        assertBlockModelPresent(context, inactiveCarrier);
        screenshot(context, "06-block-placed");
        screenshot(context, "07-block-state-off");
        context.getInput().pressKey(options -> options.keyHotbarSlots[8]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 8, 100);
        context.getInput().lookAt(PLACED_BLOCK);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(PLACED_BLOCK), 200);
        context.getInput().pressKey(options -> options.keyUse);
        context.waitFor(client -> !client.level.getBlockState(PLACED_BLOCK).equals(inactiveCarrier), 200);
        var activeCarrier = context.computeOnClient(client -> client.level.getBlockState(PLACED_BLOCK));
        assertBlockModelPresent(context, activeCarrier);
        screenshot(context, "08-block-state-on");
        pass("state-toggle", "Use input changed the live stateful block to a distinct vanilla carrier state");

        context.getInput().pressKey(options -> options.keyHotbarSlots[2]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 2, 100);
        int damageBefore = context.computeOnClient(client ->
                client.player.getInventory().getSelectedItem().getDamageValue());
        context.getInput().lookAt(PLACED_BLOCK);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(PLACED_BLOCK), 200);
        context.getInput().holdKey(options -> options.keyAttack);
        try {
            context.waitFor(client -> client.level.getBlockState(PLACED_BLOCK).isAir(), 600);
        } finally {
            context.getInput().releaseKey(options -> options.keyAttack);
        }
        context.waitFor(client -> client.player.getInventory().getSelectedItem().getDamageValue() == damageBefore + 1,
                200);
        screenshot(context, "09-block-broken");
        pass("break", "Attack input broke the server-authoritative custom block and damaged the real tool");
    }

    private void exerciseSemanticUse(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 0, 100);
        context.computeOnClient(client -> {
            var stack = client.player.getInventory().getSelectedItem();
            var carrier = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!Identifier.DEFAULT_NAMESPACE.equals(carrier.getNamespace())) {
                throw new AssertionError("Mapped item leaked a non-vanilla client registry id: " + carrier);
            }
            if (!stack.getHoverName().getString().contains("PolyMc Reborn")) {
                throw new AssertionError("Mapped item did not preserve its server-provided display name");
            }
            Identifier modelId = stack.get(DataComponents.ITEM_MODEL);
            if (modelId == null) {
                modelId = carrier;
            }
            if (client.getModelManager().getItemModel(modelId) instanceof MissingItemModel) {
                throw new AssertionError("Mapped item resolved to Minecraft's missing item model: " + modelId);
            }
            return true;
        });
        screenshot(context, "04-item-held");

        context.getInput().pressKey(options -> options.keyHotbarSlots[3]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 3, 100);
        int before = context.computeOnClient(client -> {
            var stack = client.player.getInventory().getSelectedItem();
            var carrier = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!Identifier.DEFAULT_NAMESPACE.equals(carrier.getNamespace())) {
                throw new AssertionError("Semantic item leaked a non-vanilla client registry id: " + carrier);
            }
            if (stack.get(DataComponents.CONSUMABLE) == null) {
                throw new AssertionError("Semantic food projection is not consumable on the client: " + carrier);
            }
            return stack.getCount();
        });
        context.waitFor(client -> client.player.getFoodData().getFoodLevel() == 10, 200);
        context.getInput().lookAt(0.0F, -70.0F);
        context.getInput().holdKey(options -> options.keyUse);
        try {
            context.waitFor(client -> client.player.getInventory().getSelectedItem().getCount() < before, 400);
        } finally {
            context.getInput().releaseKey(options -> options.keyUse);
        }
        int after = context.computeOnClient(client ->
                client.player.getInventory().getSelectedItem().getCount());
        if (after != before - 1) {
            throw new AssertionError("Semantic food use consumed " + (before - after)
                    + " items instead of exactly one");
        }
        screenshot(context, "05-item-after-use");
        pass("semantic-item-use", "Food use consumed exactly one real custom item through vanilla input");
    }

    private void exerciseDropAndPickup(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 0, 100);
        String before = context.computeOnClient(RebornClientPlaytest::inventoryFingerprint);
        context.computeOnClient(client -> {
            var stack = client.player.getInventory().getSelectedItem();
            if (stack.isEmpty() || !stack.getHoverName().getString().contains("PolyMc Reborn Fixture Item")) {
                throw new AssertionError("Mapped fixture item is unavailable before the drop scenario");
            }
            return true;
        });

        // Q is sent through the live key binding. Waiting before walking guarantees that the
        // server observes at least one authoritative inventory-absent interval, including the
        // vanilla owner pickup delay, instead of accepting a client-only slot animation.
        context.getInput().lookAt(0.0F, 80.0F);
        context.getInput().pressKey(options -> options.keyDrop);
        context.waitFor(client -> client.player.getInventory().getSelectedItem().isEmpty(), 200);
        context.waitFor(client -> droppedMappedItem(client) != null, 200);
        context.waitTicks(45);
        float pickupYaw = context.computeOnClient(client -> {
            ItemEntity dropped = droppedMappedItem(client);
            if (dropped == null) {
                throw new AssertionError("Dropped mapped item disappeared before pickup movement");
            }
            double dx = dropped.getX() - client.player.getX();
            double dz = dropped.getZ() - client.player.getZ();
            return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        });
        context.getInput().lookAt(pickupYaw, 20.0F);
        context.getInput().holdKey(options -> options.keyUp);
        try {
            context.waitFor(client -> {
                var stack = client.player.getInventory().getItem(0);
                return stack.getCount() == 1
                        && stack.getHoverName().getString().contains("PolyMc Reborn Fixture Item");
            }, 300);
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }

        String after = context.computeOnClient(RebornClientPlaytest::inventoryFingerprint);
        if (!before.equals(after)) {
            throw new AssertionError("Dropped custom item did not return with the same inventory/component fingerprint");
        }
        pass("item-drop-pickup",
                "Pressed the bound drop key, observed the mapped item leave inventory and picked it up intact");
    }

    private void exerciseGui(ClientGameTestContext context) {
        openProjectedGui(context);
        assertMenuStack(context, 0, Items.DIAMOND, 16);
        assertMenuStack(context, 1, Items.EMERALD, 8);
        screenshot(context, "10-gui-open");

        // Generic 9x3 screens are 176x166 logical pixels. TestInput converts the logical cursor
        // position through the active window scale and sends the same mouse path as a player.
        var firstSlot = containerSlot(context, 0);
        var secondSlot = containerSlot(context, 1);
        var thirdSlot = containerSlot(context, 2);
        var fourthSlot = containerSlot(context, 3);
        var fifthSlot = containerSlot(context, 4);
        context.getInput().setCursorPos(firstSlot[0], firstSlot[1]);
        context.getInput().pressMouse(1);
        context.waitTicks(2);
        context.getInput().setCursorPos(thirdSlot[0], thirdSlot[1]);
        context.getInput().pressMouse(0);
        context.waitTicks(2);
        assertMenuStack(context, 0, Items.DIAMOND, 8);
        assertMenuStack(context, 2, Items.DIAMOND, 8);
        context.computeOnClient(client -> {
            if (!client.player.containerMenu.getCarried().isEmpty()) {
                throw new AssertionError("Right-click/pickup transaction left a ghost carried stack");
            }
            return true;
        });

        context.getInput().setCursorPos(firstSlot[0], firstSlot[1]);
        context.waitTicks(1);
        pressModifiedContainerSlot(context, 0, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        context.waitTicks(2);
        context.waitFor(client -> client.player.containerMenu.slots.get(0).getItem().isEmpty(), 100);
        context.computeOnClient(client -> {
            if (!client.player.containerMenu.getCarried().isEmpty()) {
                throw new AssertionError("Shift-click was interpreted as pickup and left a carried stack");
            }
            return true;
        });
        screenshot(context, "11-gui-after-shift-click");

        String beforeDrag = context.computeOnClient(RebornClientPlaytest::projectedMenuFingerprint);
        // Pick up the source stack first. A real QUICK_CRAFT gesture starts only while the
        // cursor is already carrying a stack; merely pressing an occupied source and releasing
        // over another slot is a PICKUP transaction and intentionally leaves the stack carried.
        context.getInput().setCursorPos(thirdSlot[0], thirdSlot[1]);
        context.getInput().pressMouse(0);
        context.waitFor(client -> !client.player.containerMenu.getCarried().isEmpty(), 100);
        context.getInput().setCursorPos(fourthSlot[0], fourthSlot[1]);
        context.getInput().holdMouse(0);
        context.waitTicks(1);
        context.getInput().setCursorPos(fifthSlot[0], fifthSlot[1]);
        context.waitTicks(1);
        context.getInput().releaseMouse(0);
        context.waitTicks(2);
        String afterDrag = context.computeOnClient(RebornClientPlaytest::projectedMenuFingerprint);
        if (beforeDrag.equals(afterDrag)) {
            throw new AssertionError("Quick-craft drag input produced no authoritative slot change");
        }
        context.computeOnClient(client -> {
            if (!client.player.containerMenu.getCarried().isEmpty()) {
                throw new AssertionError("Quick-craft drag left a ghost carried stack");
            }
            return true;
        });

        context.getInput().setCursorPos(secondSlot[0], secondSlot[1]);
        context.waitTicks(1);
        context.getInput().pressKey(options -> options.keyHotbarSlots[5]);
        context.waitFor(client -> client.player.getInventory().getItem(5).is(Items.EMERALD)
                && client.player.getInventory().getItem(5).getCount() == 8, 100);
        context.waitFor(client -> client.player.containerMenu.slots.get(1).getItem().isEmpty(), 100);
        screenshot(context, "12-gui-after-hotbar-swap");

        int offhandSource = context.computeOnClient(client -> {
            for (int slot = 0; slot < 27; slot++) {
                if (client.player.containerMenu.slots.get(slot).getItem().is(Items.DIAMOND)) {
                    return slot;
                }
            }
            throw new AssertionError("No projected diamond stack remained for offhand swap");
        });
        var offhandSlot = containerSlot(context, offhandSource);
        context.getInput().setCursorPos(offhandSlot[0], offhandSlot[1]);
        // Container hotkeys act on AbstractContainerScreen.hoveredSlot. Give the render thread
        // one tick to process the real cursor movement before sending the swap-offhand key.
        context.waitTicks(1);
        context.getInput().pressKey(options -> options.keySwapOffhand);
        context.waitFor(client -> client.player.getOffhandItem().is(Items.DIAMOND), 100);
        assertGuiInventoryIntegrity(context);
        String beforeReopen = context.computeOnClient(RebornClientPlaytest::projectedMenuFingerprint);
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitFor(client -> !(client.screen instanceof AbstractContainerScreen<?>), 100);
        openProjectedGui(context);
        // The vanilla screen is constructed before its authoritative slot-content packet can be
        // applied. Wait for that bounded network synchronization instead of sampling the new,
        // temporarily empty menu on its first rendered frame.
        context.waitFor(client -> beforeReopen.equals(projectedMenuFingerprint(client)), 200);
        String afterReopen = context.computeOnClient(RebornClientPlaytest::projectedMenuFingerprint);
        if (!beforeReopen.equals(afterReopen)) {
            throw new AssertionError("Projected container changed across close/reopen");
        }
        screenshot(context, "13-gui-reopened");
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitFor(client -> !(client.screen instanceof AbstractContainerScreen<?>), 100);
        pass("gui-transactions", "Right-clicked, dragged, shift-clicked, hotbar-swapped, offhand-swapped, closed and reopened a vanilla container screen");
    }

    private void exerciseEntity(ClientGameTestContext context) {
        context.waitFor(client -> clientSurrogate(client) != null, 200);
        context.computeOnClient(client -> {
            Entity target = clientSurrogate(client);
            if (target == null || !target.isCurrentlyGlowing()) {
                throw new AssertionError("Projected entity did not synchronize its glowing metadata");
            }
            if (!(target instanceof net.minecraft.world.entity.LivingEntity living)
                    || !living.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD)
                    .is(Items.GOLDEN_HELMET)) {
                throw new AssertionError("Projected entity did not receive explicit vanilla equipment");
            }
            if (target.getPassengers().size() != 1
                    || target.getPassengers().getFirst().getType() != EntityType.PARROT) {
                throw new AssertionError("Projected entity did not receive its explicit passenger");
            }
            return true;
        });
        var initialPosition = context.computeOnClient(client -> clientSurrogate(client).position());
        float initialYaw = context.computeOnClient(client -> clientSurrogate(client).getYRot());
        screenshot(context, "14-entity-spawned");
        context.waitFor(client -> {
            Entity target = clientSurrogate(client);
            return target != null && target.position().distanceTo(initialPosition) > 0.02D
                    && Math.abs(target.getYRot() - initialYaw) > 1.0F;
        }, 200);
        screenshot(context, "15-entity-moved");

        aimAtProjectedEntity(context);
        context.getInput().pressKey(options -> options.keyUse);
        context.waitTicks(4);
        context.computeOnClient(client -> {
            if (client.screen != null) {
                throw new AssertionError("Entity use missed the surrogate and opened another screen");
            }
            return true;
        });

        // The authoritative source keeps moving. Recompute the live ray target immediately before
        // attack so both actions are genuine EntityHitResult inputs rather than stale camera aim.
        aimAtProjectedEntity(context);
        context.getInput().pressKey(options -> options.keyAttack);
        context.waitTicks(4);
        screenshot(context, "16-entity-interacted");
        pass("entity-use-attack", "Use and attack input targeted the tracked vanilla surrogate entity");
    }

    private void exercisePropertyGui(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyHotbarSlots[6]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 6, 100);
        context.getInput().lookAt(0.0F, -70.0F);
        context.getInput().pressKey(options -> options.keyUse);
        context.waitFor(client -> client.player.containerMenu instanceof FurnaceMenu
                && client.screen instanceof AbstractContainerScreen<?>, 200);
        assertMenuStack(context, 0, Items.RAW_IRON, 1);
        assertMenuStack(context, 1, Items.COAL, 1);
        float initialProgress = context.computeOnClient(client ->
                ((FurnaceMenu) client.player.containerMenu).getBurnProgress());
        screenshot(context, "14-property-gui-start");
        context.waitFor(client -> ((FurnaceMenu) client.player.containerMenu).getBurnProgress()
                > initialProgress, 200);
        screenshot(context, "15-property-gui-progress");
        context.waitFor(client -> client.player.containerMenu.slots.get(2).getItem().is(Items.IRON_INGOT), 300);
        screenshot(context, "16-property-gui-complete");

        var output = furnaceSlot(context, 124.0D, 43.0D);
        context.getInput().setCursorPos(output[0], output[1]);
        context.waitTicks(1);
        pressModifiedLogical(context, 124.0D, 43.0D, org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT);
        context.waitFor(client -> client.player.containerMenu.slots.get(2).getItem().isEmpty(), 100);
        context.computeOnClient(client -> {
            boolean received = false;
            for (int slot = 0; slot < client.player.getInventory().getContainerSize(); slot++) {
                received |= client.player.getInventory().getItem(slot).is(Items.IRON_INGOT);
            }
            if (!received || !client.player.containerMenu.getCarried().isEmpty()) {
                throw new AssertionError("Furnace result was not transferred authoritatively");
            }
            return true;
        });
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitFor(client -> !(client.screen instanceof AbstractContainerScreen<?>), 100);
        context.getInput().lookAt(0.0F, -70.0F);
        context.getInput().pressKey(options -> options.keyUse);
        context.waitFor(client -> client.player.containerMenu instanceof FurnaceMenu, 200);
        context.waitFor(client -> client.player.containerMenu.slots.get(2).getItem().isEmpty(), 100);
        context.getInput().pressKey(options -> options.keyInventory);
        context.waitFor(client -> !(client.screen instanceof AbstractContainerScreen<?>), 100);
        pass("property-gui", "Observed burn/cook progress, transferred output, and reopened the authoritative furnace");
    }

    private void exerciseExternalContent(ClientGameTestContext context) {
        if ("none".equals(externalMode)) {
            return;
        }
        context.getInput().pressKey(options -> options.keyHotbarSlots[7]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 7, 100);
        context.computeOnClient(client -> {
            var stack = client.player.getInventory().getSelectedItem();
            if (stack.isEmpty() || !stack.getHoverName().getString().startsWith("External Matrix ")) {
                throw new AssertionError("Server did not supply the locked third-party content item");
            }
            Identifier carrier = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (!Identifier.DEFAULT_NAMESPACE.equals(carrier.getNamespace())) {
                throw new AssertionError("Third-party registry id leaked to the unmodified client: " + carrier);
            }
            return true;
        });
        if ("armor".equals(externalMode)) {
            context.getInput().lookAt(0.0F, -70.0F);
            context.getInput().pressKey(options -> options.keyUse);
            context.waitFor(client -> !client.player.getItemBySlot(
                    net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty(), 200);
            context.computeOnClient(client -> {
                Identifier carrier = BuiltInRegistries.ITEM.getKey(client.player.getItemBySlot(
                        net.minecraft.world.entity.EquipmentSlot.HEAD).getItem());
                if (!Identifier.DEFAULT_NAMESPACE.equals(carrier.getNamespace())) {
                    throw new AssertionError("Equipped third-party item leaked its registry id");
                }
                return true;
            });
            screenshot(context, "18-external-content");
            pass("external-content", "Equipped one real third-party armor item through vanilla use input");
            return;
        }
        if (!"block".equals(externalMode)) {
            throw new IllegalArgumentException("Unknown external playtest mode " + externalMode);
        }
        context.getInput().lookAt(EXTERNAL_SUPPORT);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(EXTERNAL_SUPPORT), 200);
        context.getInput().pressKey(options -> options.keyUse);
        context.waitFor(client -> !client.level.getBlockState(EXTERNAL_BLOCK).isAir(), 200);
        assertBlockModelPresent(context,
                context.computeOnClient(client -> client.level.getBlockState(EXTERNAL_BLOCK)));
        screenshot(context, "18-external-content");
        context.getInput().pressKey(options -> options.keyHotbarSlots[2]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 2, 100);
        context.getInput().lookAt(EXTERNAL_BLOCK);
        context.waitFor(client -> client.hitResult instanceof BlockHitResult hit
                && hit.getBlockPos().equals(EXTERNAL_BLOCK), 200);
        context.getInput().holdKey(options -> options.keyAttack);
        try {
            context.waitFor(client -> client.level.getBlockState(EXTERNAL_BLOCK).isAir(), 600);
        } finally {
            context.getInput().releaseKey(options -> options.keyAttack);
        }
        pass("external-content", "Placed and broke one real third-party full-cube block through vanilla input");
    }

    private void aimAtProjectedEntity(ClientGameTestContext context) {
        var aim = context.computeOnClient(client -> {
            Entity target = clientSurrogate(client);
            if (target == null) {
                throw new IllegalStateException("Vanilla surrogate disappeared before aiming");
            }
            if (target.getCustomName() == null
                    || !target.getCustomName().getString().contains("Projected Fixture Entity")) {
                throw new AssertionError("Surrogate did not expose the synchronized fixture name");
            }
            var eye = client.player.getEyePosition();
            var center = target.getBoundingBox().getCenter();
            double dx = center.x - eye.x;
            double dy = center.y - eye.y;
            double dz = center.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            return new EntityAim(
                    (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
                    (float) -Math.toDegrees(Math.atan2(dy, horizontal)),
                    eye.distanceTo(center));
        });
        if (aim.distance() > 3.0D) {
            throw new AssertionError("Projected fixture entity is outside vanilla survival pick range: "
                    + aim.distance());
        }
        context.getInput().lookAt(aim.yaw(), aim.pitch());
        context.waitFor(client -> client.hitResult instanceof EntityHitResult hit
                && hit.getEntity().getType() == EntityType.ARMOR_STAND, 1200);
    }

    private void openProjectedGui(ClientGameTestContext context) {
        context.getInput().pressKey(options -> options.keyHotbarSlots[0]);
        context.waitFor(client -> client.player.getInventory().getSelectedSlot() == 0, 100);
        context.getInput().lookAt(0.0F, -70.0F);
        context.getInput().pressKey(options -> options.keyUse);
        context.waitFor(client -> client.screen instanceof AbstractContainerScreen<?>, 200);
    }

    private void assertGuiInventoryIntegrity(ClientGameTestContext context) {
        context.computeOnClient(client -> {
            int diamonds = 0;
            int emeralds = 0;
            for (var slot : client.player.containerMenu.slots) {
                var stack = slot.getItem();
                if (stack.is(Items.DIAMOND)) {
                    diamonds += stack.getCount();
                } else if (stack.is(Items.EMERALD)) {
                    emeralds += stack.getCount();
                }
            }
            var carried = client.player.containerMenu.getCarried();
            if (carried.is(Items.DIAMOND)) {
                diamonds += carried.getCount();
            } else if (carried.is(Items.EMERALD)) {
                emeralds += carried.getCount();
            }
            var offhand = client.player.getOffhandItem();
            if (offhand.is(Items.DIAMOND)) {
                diamonds += offhand.getCount();
            } else if (offhand.is(Items.EMERALD)) {
                emeralds += offhand.getCount();
            }
            if (diamonds != 16 || emeralds != 8) {
                throw new AssertionError("Projected GUI client inventory diverged: diamonds="
                        + diamonds + ", emeralds=" + emeralds);
            }
            return true;
        });
    }

    private static void assertMenuStack(ClientGameTestContext context, int slot,
                                        net.minecraft.world.item.Item item, int count) {
        context.computeOnClient(client -> {
            var stack = client.player.containerMenu.slots.get(slot).getItem();
            if (!stack.is(item) || stack.getCount() != count) {
                throw new AssertionError("Projected GUI slot " + slot + " expected "
                        + BuiltInRegistries.ITEM.getKey(item) + " x" + count + " but saw "
                        + BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount());
            }
            return true;
        });
    }

    private static String projectedMenuFingerprint(net.minecraft.client.Minecraft client) {
        var value = new StringBuilder();
        for (int slot = 0; slot < client.player.containerMenu.slots.size(); slot++) {
            var stack = client.player.containerMenu.slots.get(slot).getItem();
            value.append(slot).append(':').append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append(':').append(stack.getCount()).append(';');
        }
        var carried = client.player.containerMenu.getCarried();
        return value.append("carried:").append(BuiltInRegistries.ITEM.getKey(carried.getItem()))
                .append(':').append(carried.getCount()).toString();
    }

    private static void assertBlockModelPresent(ClientGameTestContext context,
                                                net.minecraft.world.level.block.state.BlockState state) {
        context.computeOnClient(client -> {
            var models = client.getModelManager().getBlockStateModelSet();
            if (models.get(state) == models.missingModel()) {
                throw new AssertionError("Mapped block state resolved to Minecraft's missing block model: " + state);
            }
            return true;
        });
    }

    private static Entity clientSurrogate(net.minecraft.client.Minecraft client) {
        if (client.level == null) {
            return null;
        }
        Entity result = null;
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity.getType() == EntityType.ARMOR_STAND) {
                if (result != null) {
                    throw new AssertionError("More than one vanilla surrogate represents the explicit fixture entity");
                }
                result = entity;
            }
        }
        return result;
    }

    private static ItemEntity droppedMappedItem(net.minecraft.client.Minecraft client) {
        if (client.level == null) {
            return null;
        }
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof ItemEntity itemEntity
                    && itemEntity.getItem().getHoverName().getString().contains("PolyMc Reborn Fixture Item")) {
                return itemEntity;
            }
        }
        return null;
    }

    private static String inventoryFingerprint(net.minecraft.client.Minecraft client) {
        var value = new StringBuilder();
        var inventory = client.player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                value.append(slot).append(':').append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                        .append(':').append(stack.getCount()).append(':').append(stack.getDamageValue())
                        .append(':').append(stack.getHoverName().getString())
                        .append(':').append(stack.getComponents()).append(';');
            }
        }
        return value.toString();
    }

    private double[] containerSlot(ClientGameTestContext context, int slot) {
        return context.computeOnClient(client -> {
            var logical = logicalContainerSlot(client, slot);
            return windowCursor(client, logical[0], logical[1]);
        });
    }

    private void pressModifiedContainerSlot(ClientGameTestContext context, int slot, int modifiers) {
        context.runOnClient(client -> {
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
                throw new IllegalStateException("Modified container input requires an open vanilla container screen");
            }
            var logical = logicalContainerSlot(client, slot);
            var event = new MouseButtonEvent(logical[0], logical[1], new MouseButtonInfo(0, modifiers));
            if (!screen.mouseClicked(event, false)) {
                throw new AssertionError("Modified container mouse press was not handled for slot " + slot);
            }
            screen.mouseReleased(event);
        });
    }

    private double[] furnaceSlot(ClientGameTestContext context, double x, double y) {
        return context.computeOnClient(client -> windowCursor(client,
                (client.getWindow().getGuiScaledWidth() - 176) / 2.0D + x,
                (client.getWindow().getGuiScaledHeight() - 166) / 2.0D + y));
    }

    private void pressModifiedLogical(ClientGameTestContext context, double x, double y, int modifiers) {
        context.runOnClient(client -> {
            if (!(client.screen instanceof AbstractContainerScreen<?> screen)) {
                throw new IllegalStateException("Modified input requires a vanilla container screen");
            }
            double logicalX = (client.getWindow().getGuiScaledWidth() - 176) / 2.0D + x;
            double logicalY = (client.getWindow().getGuiScaledHeight() - 166) / 2.0D + y;
            var event = new MouseButtonEvent(logicalX, logicalY, new MouseButtonInfo(0, modifiers));
            if (!screen.mouseClicked(event, false)) {
                throw new AssertionError("Modified property GUI input was not handled");
            }
            screen.mouseReleased(event);
        });
    }

    private static double[] logicalContainerSlot(net.minecraft.client.Minecraft client, int slot) {
        return new double[]{
                (client.getWindow().getGuiScaledWidth() - 176) / 2.0D + 17.0D + (slot % 9) * 18.0D,
                (client.getWindow().getGuiScaledHeight() - 166) / 2.0D + 27.0D + (slot / 9) * 18.0D};
    }

    private static double[] windowCursor(net.minecraft.client.Minecraft client,
                                         double logicalX, double logicalY) {
        var window = client.getWindow();
        return new double[]{
                logicalX * window.getScreenWidth() / window.getGuiScaledWidth(),
                logicalY * window.getScreenHeight() / window.getGuiScaledHeight()};
    }

    private void screenshot(ClientGameTestContext context, String name) {
        Path output = context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix()
                .withDestinationDir(screenshotsDirectory));
        addStep("screenshot:" + name, true, output.getFileName().toString(), output.getFileName().toString(), "");
    }

    private void disconnect(ClientGameTestContext context) {
        context.runOnClient(client -> {
            // This is Minecraft's normal multiplayer disconnect path: it first closes the live
            // ClientLevel connection, then performs client teardown. Calling disconnect(Screen,
            // false) directly would only clear local state and leave the server player to time out.
            client.disconnectFromWorld(Component.literal("PolyMc Reborn playtest normal disconnect"));
            client.setScreen(new TitleScreen());
        });
        context.waitFor(client -> client.level == null && client.screen instanceof TitleScreen, 400);
        // disconnectFromWorld clears server packs through an asynchronous resource reload. Do not
        // start another connection until the old pack is observably gone, otherwise its pending
        // teardown can race with and erase the reconnect's newly accepted pack.
        context.waitFor(client -> client.getResourceManager().getResource(PACK_PROBE).isEmpty()
                && client.getOverlay() == null, 1200);
        // Give the closed vanilla Connection a small bounded propagation window. The independent
        // post-run server report—not this client driver—verifies both DISCONNECT callbacks exactly.
        context.waitTicks(20);
    }

    private void ensureDisconnected(ClientGameTestContext context) {
        boolean connected = context.computeOnClient(client -> client.level != null);
        if (connected) {
            disconnect(context);
        } else {
            context.runOnClient(client -> {
                if (!(client.screen instanceof TitleScreen)) {
                    client.setScreen(new TitleScreen());
                }
            });
            context.waitFor(client -> client.screen instanceof TitleScreen, 200);
        }
    }

    private void pass(String id, String detail) {
        addStep(id, true, detail, "", "");
    }

    private void fail(String id, String detail) {
        addStep(id, false, detail, "", detail);
    }

    private void addStep(String id, boolean passed, String detail, String screenshot, String failureReason) {
        Instant finished = Instant.now();
        var spec = scenarioSpec(id);
        steps.add(new Step(id, passed, detail, lastStepBoundary.toString(), finished.toString(),
                spec.preconditions(), spec.actualInput(), spec.clientAssertions(), spec.serverAssertions(),
                spec.timeoutTicks(), screenshot, failureReason, spec.cleanup(), spec.registryIds(),
                spec.mappingDecision()));
        lastStepBoundary = finished;
    }

    private void writeReports(boolean success) {
        try {
            Files.createDirectories(reportDirectory);
            var json = new StringBuilder();
            json.append("{\n  \"schema_version\": 1,\n")
                    .append("  \"result\": \"").append(success ? "passed" : "failed").append("\",\n")
                    .append("  \"minecraft_version\": \"26.1.2\",\n")
                    .append("  \"client_kind\": \"isolated-fabric-client-driver\",\n")
                    .append("  \"client_id\": \"").append(escape(clientId)).append("\",\n")
                    .append("  \"scenario\": \"").append(escape(scenario)).append("\",\n")
                    .append("  \"client_profile\": \"VANILLA\",\n")
                    .append("  \"resource_pack_sha256\": \"").append(escape(measuredResourcePackSha256))
                    .append("\",\n")
                    .append("  \"resource_pack_sha1\": \"").append(escape(measuredResourcePackSha1))
                    .append("\",\n")
                    .append("  \"resource_pack_expected_sha256\": \"")
                    .append(escape(expectedResourcePackSha256)).append("\",\n")
                    .append("  \"resource_pack_expected_sha1\": \"")
                    .append(escape(expectedResourcePackSha1)).append("\",\n")
                    .append("  \"resource_pack_bytes\": ").append(measuredResourcePackBytes).append(",\n")
                    .append("  \"completed_at\": \"").append(Instant.now()).append("\",\n")
                    .append("  \"steps\": [\n");
            for (int index = 0; index < steps.size(); index++) {
                var step = steps.get(index);
                json.append("    {\"id\": \"").append(escape(step.id())).append("\", \"passed\": ")
                        .append(step.passed()).append(", \"detail\": \"").append(escape(step.detail()))
                        .append("\", \"started_at\": \"").append(escape(step.startedAt()))
                        .append("\", \"finished_at\": \"").append(escape(step.finishedAt()))
                        .append("\", \"preconditions\": \"").append(escape(step.preconditions()))
                        .append("\", \"actual_input\": \"").append(escape(step.actualInput()))
                        .append("\", \"client_assertions\": \"").append(escape(step.clientAssertions()))
                        .append("\", \"server_assertions\": \"").append(escape(step.serverAssertions()))
                        .append("\", \"timeout_ticks\": ").append(step.timeoutTicks())
                        .append(", \"screenshot\": \"").append(escape(step.screenshot()))
                        .append("\", \"failure_reason\": \"").append(escape(step.failureReason()))
                        .append("\", \"cleanup\": \"").append(escape(step.cleanup()))
                        .append("\", \"registry_ids\": ").append(jsonArray(step.registryIds()))
                        .append(", \"mapping_decision\": \"").append(escape(step.mappingDecision()))
                        .append("\"}")
                        .append(index + 1 == steps.size() ? "\n" : ",\n");
            }
            json.append("  ]\n}\n");
            Files.writeString(reportDirectory.resolve("client-state.json"), json, StandardCharsets.UTF_8);

            var markdown = new StringBuilder("# PolyMc Reborn client playtest\n\n")
                    .append("Result: **").append(success ? "PASS" : "FAIL").append("**\n\n")
                    .append("This is an isolated Fabric Client GameTest driver, not a zero-mod vanilla client.\n\n")
                    .append("| Step | Result | Detail |\n|---|---:|---|\n");
            steps.forEach(step -> markdown.append("| `").append(step.id()).append("` | ")
                    .append(step.passed() ? "PASS" : "FAIL").append(" | ")
                    .append(step.detail().replace("|", "\\|")).append(" |\n"));
            Files.writeString(reportDirectory.resolve("client-state.md"), markdown, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write client playtest reports", exception);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required system property " + name);
        }
        return value;
    }

    private static String safeMessage(Throwable throwable) {
        return throwable.getMessage() == null ? "no detail" : throwable.getMessage();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String jsonArray(List<String> values) {
        var json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(escape(values.get(index))).append('"');
        }
        return json.append(']').toString();
    }

    private static ScenarioSpec scenarioSpec(String id) {
        if (id.startsWith("screenshot:")) {
            return new ScenarioSpec("A live rendered Minecraft frame exists",
                    "Fabric Client GameTest framebuffer capture",
                    "PNG is produced from the current real client frame",
                    "The associated server-authoritative scenario remains active",
                    200, "Only the bounded screenshot artifact is retained", List.of(),
                    "Inherited from the associated scenario");
        }
        return switch (id) {
            case "client-isolation" -> new ScenarioSpec("Client Fabric Loader initialized",
                    "Enumerate the live Fabric client Mod containers",
                    "Exact eight-Mod allowlist; no Reborn, Polymer, fixture or content Mod",
                    "Not applicable", 100, "Write loaded-client-mods.json", List.of(), "not-applicable");
            case "connect" -> new ScenarioSpec("Independent loopback dedicated server reports ready",
                    "Open ConnectScreen and join the loopback endpoint",
                    "Client level, player and rendered chunks exist",
                    "join_count must be exactly two after reconnect", 1200,
                    "A later disconnect always closes the network session", List.of(), "not-applicable");
            case "resource-pack-initial-connection", "resource-pack-reconnection" -> new ScenarioSpec(
                    "Required vanilla resource-pack prompt is visible",
                    "Move the virtual cursor to Proceed and issue a real mouse click",
                    "Fixture-only model resolves and downloaded ZIP hashes are measured from disk",
                    "Server-ready, server-state and measured client SHA-1/SHA-256 must agree", 1200,
                    "Downloaded cache exists only inside the fresh client run directory",
                    List.of("polymc-reborn-gametest:basic_item"), "HEURISTIC Polymer resource projection");
            case "movement-look" -> new ScenarioSpec("Client is in the deterministic arena",
                    "Set yaw/pitch and hold the bound forward key for six ticks",
                    "Position changes and yaw=35/pitch=5 are observed", "Player remains connected", 200,
                    "Release synthetic key state", List.of(), "not-applicable");
            case "hotbar" -> new ScenarioSpec("Server inventory has mapped content",
                    "Press the bound hotbar number key", "Selected slot changes to slot 2",
                    "Real server inventory remains authoritative", 100, "No held key remains", List.of(),
                    "not-applicable");
            case "semantic-item-use" -> new ScenarioSpec("Food level is exactly 10 and projected item is consumable",
                    "Select slot 4 and hold the bound use key until one authoritative consume completes",
                    "Vanilla carrier count decreases by exactly one",
                    "food_remaining=3 and semantic_use_observed=true",
                    400, "Use key is released", List.of("polymc-reborn-gametest:food_item"),
                    "HEURISTIC semantic-item-food via Polymer");
            case "item-drop-pickup" -> new ScenarioSpec(
                    "The mapped basic custom item is selected and the player is in the deterministic arena",
                    "Look down, press the bound drop key, wait through the owner delay and walk to the live ItemEntity",
                    "The selected slot becomes empty and the complete inventory/component fingerprint is restored",
                    "item_drop_observed/item_pickup_observed are true and basic_item_remaining=1",
                    600, "Drop is a one-shot key input; forward input is released after pickup",
                    List.of("polymc-reborn-gametest:basic_item"),
                    "HEURISTIC generic-item via Polymer with server-authoritative world ItemEntity");
            case "simple-block" -> new ScenarioSpec("Stone support and full-cube block item are present",
                    "Right-click place, select the real tool, then hold attack until removal",
                    "Vanilla carrier appears with a non-missing model and is removed after break",
                    "simple_block_placed_observed and simple_block_broken_observed are true", 600,
                    "Attack key is released", List.of("polymc-reborn-gametest:full_block"),
                    "HEURISTIC textured-full-cube via Polymer");
            case "place", "state-toggle", "break" -> new ScenarioSpec(
                    "Stateful full-cube fixture and deterministic support exist",
                    "Place with use, toggle with use, and break with attack",
                    "Distinct non-missing vanilla states are observed and tool damage advances exactly once",
                    "placed/state_toggle/broken probes are true and total tool_damage=2", 600,
                    "All held input is released", List.of("polymc-reborn-gametest:stateful_block"),
                    "HEURISTIC state-complete textured-full-cube via Polymer");
            case "gui-transactions", "gui-open-at-disconnect" -> new ScenarioSpec(
                    "Explicit 9x3 projection is registered over one persistent authoritative container",
                    "Right-click, pickup, Shift-click, drag, hotbar swap, offhand swap, close and reopen",
                    "Each action changes the expected slots; totals stay 16 diamonds/8 emeralds; no carried ghost",
                    "gui_open_count=3, gui_close_count=3, integrity=true and active sessions=0 at shutdown", 200,
                    "Close or disconnect the projected menu", List.of("polymc-reborn-gametest:fixture_menu"),
                    "EXPLICIT standard-9xn via Polymer menu sanitization");
            case "property-gui" -> new ScenarioSpec(
                    "An explicit three-slot property adapter owns one real server container and four properties",
                    "Open the furnace, observe progress, Shift-click its result, close and reopen",
                    "Vanilla FurnaceMenu shows bounded progress and transfers one iron ingot without a ghost stack",
                    "property ticks reach completion once; two opens use one authoritative state",
                    400, "Close the projected furnace screen",
                    List.of("polymc-reborn-gametest:property_menu"),
                    "EXPLICIT furnace property projection");
            case "entity-use-attack" -> new ScenarioSpec(
                    "Exactly one tracked Armor Stand surrogate is alive and within vanilla reach",
                    "Aim through EntityHitResult, then press use once and attack once",
                    "Name, glow, position, rotation, passenger and vanilla equipment synchronize; the main surrogate stays unique",
                    "entity callbacks are exact and passenger/equipment packet counters are at least two", 200,
                    "Entity interaction keys are released; server owns session cleanup",
                    List.of("polymc-reborn-gametest:fixture_entity"),
                    "EXPLICIT Polymer Virtual Entity vanilla-surrogate");
            case "external-content" -> new ScenarioSpec(
                    "A hash-locked 26.1.2 third-party content Mod is installed only on the server",
                    "Use/equip its real item or place and break its real full-cube block",
                    "Only a vanilla carrier/state is visible and the interaction completes",
                    "external_mod_loaded and external_content_passed are true",
                    600, "Release held input; client never installs the tested Mod", List.of(),
                    "Server report records the exact external registry decision");
            case "disconnect", "reconnect" -> new ScenarioSpec("A live projected session exists",
                    "Disconnect to TitleScreen, reconnect, and accept the required pack again",
                    "Inventory/component fingerprint is unchanged and exactly one surrogate returns",
                    "disconnect_count=2; GUI session cleanup completes; mapping and pack hashes stay stable", 1200,
                    "Finalizer disconnects the second connection cleanly", List.of(), "frozen MappingPlan unchanged");
            case "cleanup" -> new ScenarioSpec("Test finalizer runs", "Request a normal client disconnect",
                    "TitleScreen is reached", "Orchestrator requests a normal dedicated-server stop", 400,
                    "No live client connection remains", List.of(), "not-applicable");
            default -> new ScenarioSpec("Previous scenario state is available", "Execute the recorded real input",
                    "Recorded assertion completed", "Server-state report supplies authoritative checks", 200,
                    "Finalizer performs bounded disconnect", List.of(), "not-applicable");
        };
    }

    private record Step(String id, boolean passed, String detail, String startedAt, String finishedAt,
                        String preconditions, String actualInput, String clientAssertions,
                        String serverAssertions, int timeoutTicks, String screenshot, String failureReason,
                        String cleanup, List<String> registryIds, String mappingDecision) {
    }

    private record ScenarioSpec(String preconditions, String actualInput, String clientAssertions,
                                String serverAssertions, int timeoutTicks, String cleanup,
                                List<String> registryIds, String mappingDecision) {
    }

    private record EntityAim(float yaw, float pitch, double distance) {
    }

    private record DownloadedPack(Path path, String sha256, String sha1, long bytes) {
    }
}
