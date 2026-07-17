/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

/** Extension entrypoint loaded from the {@code polymc-reborn} Fabric key. */
public interface PolyMcRebornEntrypoint {
    default void registerCompatibility(ExtensionRegistry registry) {
    }

    default void registerResources(ResourceRegistry resources) {
    }

    interface ExtensionRegistry {
        void registerProvider(CompatibilityProvider provider);
    }

    interface ResourceRegistry {
        void register(String ownerMod, ResourceContributor contributor);
    }
}
