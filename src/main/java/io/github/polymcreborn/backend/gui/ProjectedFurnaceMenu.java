/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.gui;

import io.github.polymcreborn.api.gui.GuiProjection;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.FurnaceMenu;

import java.util.Objects;

/** Vanilla furnace surface backed directly by an explicit real container and read-only properties. */
public final class ProjectedFurnaceMenu extends FurnaceMenu {
    private final Container authoritativeContainer;
    private final GuiProjectionSessionManager sessions;
    private final GuiProjectionSessionManager.Session session;
    private boolean closed;

    public ProjectedFurnaceMenu(int containerId, Inventory inventory, Identifier adapterId,
                                GuiProjection projection, GuiProjectionSessionManager sessions) {
        super(containerId, inventory,
                Objects.requireNonNull(projection, "projection").authoritativeContainer(),
                new ClampedPropertyView(projection.propertyData().orElseThrow()));
        this.authoritativeContainer = projection.authoritativeContainer();
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.session = sessions.open(inventory.player.getUUID(), containerId,
                Objects.requireNonNull(adapterId, "adapterId"));
        try {
            authoritativeContainer.startOpen(inventory.player);
        } catch (RuntimeException exception) {
            sessions.close(session);
            throw exception;
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return !closed && authoritativeContainer.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!closed) {
            closed = true;
            authoritativeContainer.stopOpen(player);
            sessions.close(session);
        }
    }

    private record ClampedPropertyView(ContainerData source) implements ContainerData {
        private ClampedPropertyView {
            Objects.requireNonNull(source, "source");
            if (source.getCount() < 4) {
                throw new IllegalArgumentException("furnace property source needs at least four values");
            }
        }

        @Override
        public int get(int index) {
            if (index < 0 || index >= 4) {
                return 0;
            }
            int value = Math.clamp(source.get(index), 0, 32767);
            if (index == 0) {
                return Math.min(value, Math.max(0, get(1)));
            }
            if (index == 2) {
                return Math.min(value, Math.max(0, get(3)));
            }
            return value;
        }

        @Override
        public void set(int index, int value) {
            // Client property claims never become server authority.
        }

        @Override
        public int getCount() {
            return 4;
        }
    }
}
