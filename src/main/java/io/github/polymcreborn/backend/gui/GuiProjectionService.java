/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import eu.pb4.polymer.core.api.other.PolymerMenuUtils;
import io.github.polymcreborn.api.gui.GuiProjectionRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import io.github.polymcreborn.api.gui.GuiProjectionKind;

/** Opens explicitly registered vanilla container projections and owns their bounded sessions. */
public final class GuiProjectionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GuiProjectionService.class);

    private final GuiProjectionRegistry.Snapshot registrations;
    private final GuiProjectionSessionManager sessions;
    private final AtomicLong rejectedOpenCount = new AtomicLong();
    private final AtomicLong failedOpenCount = new AtomicLong();
    private volatile Set<MenuType<?>> activeTypes = Set.of();
    private boolean installed;

    public GuiProjectionService(GuiProjectionRegistry.Snapshot registrations, int maximumSessions) {
        this.registrations = Objects.requireNonNull(registrations, "registrations");
        this.sessions = new GuiProjectionSessionManager(maximumSessions);
    }

    public synchronized void install() {
        if (installed) {
            return;
        }
        Set<MenuType<?>> active = Collections.newSetFromMap(new IdentityHashMap<>());
        registrations.entries().forEach(entry -> {
            var type = entry.adapter().serverMenuType();
            if (PolymerMenuUtils.isPolymerType(type)) {
                LOGGER.info("Skipping explicit GUI adapter {} because a native Polymer menu type already exists",
                        entry.adapter().id());
                return;
            }
            PolymerMenuUtils.registerType(type);
            active.add(type);
        });
        activeTypes = Collections.unmodifiableSet(active);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                sessions.disconnect(handler.player.getUUID()));
        installed = true;
    }

    /**
     * Projects one real source menu. Unknown menu types fail closed and are not opened.
     * The source menu is used only to resolve its authoritative container and adapter policy.
     */
    public OptionalInt open(ServerPlayer player, AbstractContainerMenu sourceMenu, Component title) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(sourceMenu, "sourceMenu");
        Objects.requireNonNull(title, "title");
        if (!installed || !activeTypes.contains(sourceMenu.getType())) {
            rejectedOpenCount.incrementAndGet();
            return OptionalInt.empty();
        }
        var adapter = registrations.find(sourceMenu.getType()).orElse(null);
        if (adapter == null) {
            rejectedOpenCount.incrementAndGet();
            return OptionalInt.empty();
        }
        try {
            var projection = adapter.project(sourceMenu, player);
            var provider = new MenuProvider() {
                @Override
                public Component getDisplayName() {
                    return title;
                }

                @Override
                public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player menuPlayer) {
                    if (menuPlayer != player) {
                        throw new IllegalStateException("Projected menu player changed during open");
                    }
                    return projection.kind() == GuiProjectionKind.FURNACE
                            ? new ProjectedFurnaceMenu(containerId, inventory, adapter.id(), projection, sessions)
                            : new ProjectedContainerMenu(containerId, inventory, adapter.id(), projection, sessions);
                }
            };
            return player.openMenu(provider);
        } catch (RuntimeException exception) {
            failedOpenCount.incrementAndGet();
            LOGGER.warn("Explicit GUI adapter {} failed closed for player {}", adapter.id(),
                    player.getGameProfile().name(), exception);
            return OptionalInt.empty();
        }
    }

    public int activeSessionCount() {
        return sessions.activeCount();
    }

    /** Closes every projected GUI session; safe to call repeatedly. */
    public int closeAll() {
        return sessions.clear();
    }

    public long rejectedOpenCount() {
        return rejectedOpenCount.get();
    }

    public long failedOpenCount() {
        return failedOpenCount.get();
    }
}
