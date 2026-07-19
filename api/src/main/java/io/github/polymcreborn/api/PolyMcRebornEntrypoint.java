/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

import io.github.polymcreborn.api.entity.EntityProjectionRegistry;
import io.github.polymcreborn.api.gui.GuiProjectionRegistry;

/** Extension entrypoint loaded from the {@code polymc-reborn} Fabric key. */
public interface PolyMcRebornEntrypoint {
    default void registerCompatibility(ExtensionRegistry registry) {
    }

    default void registerResources(ResourceRegistry resources) {
    }

    /** Registers explicit-only entity projections before the registry is frozen. */
    default void registerEntityProjections(EntityProjectionRegistry registry) {
    }

    /** Registers explicit-only standard-container projections before the registry is frozen. */
    default void registerGuiProjections(GuiProjectionRegistry registry) {
    }

    interface ExtensionRegistry {
        void registerProvider(CompatibilityProvider provider);
    }

    interface ResourceRegistry {
        void register(String ownerMod, ResourceContributor contributor);
    }
}
