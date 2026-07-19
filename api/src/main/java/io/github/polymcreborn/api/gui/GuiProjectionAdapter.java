/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

/** Explicit adapter for one registered server menu type. No automatic GUI guessing is performed. */
public interface GuiProjectionAdapter {
    /** Stable namespaced adapter identifier used in diagnostics and persistence. */
    Identifier id();

    /** The real modded server menu type handled by this adapter. */
    MenuType<?> serverMenuType();

    /**
     * Resolves a bounded vanilla projection while retaining the source menu's real container.
     * Implementations must return the actual authoritative {@code Container}, never a copied view.
     */
    GuiProjection project(AbstractContainerMenu sourceMenu, ServerPlayer player);
}
