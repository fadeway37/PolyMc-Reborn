/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.concurrent.atomic.AtomicBoolean;

/** Registers overlays before freeze, then locks semantic item carriers after vanilla binds item components. */
public final class RegistryFreezeHook {
    private static final AtomicBoolean FIRED = new AtomicBoolean();

    private RegistryFreezeHook() {
    }

    public static void beforeFreeze(Registry<?> registry) {
        // Fabric freezes built-in registries sequentially after Mod initialization.
        // Planning must run at the first boundary so Registry Sync Manipulator can
        // mark auxiliary entries before any earlier registry becomes immutable.
        if (FIRED.compareAndSet(false, true)) {
            PolyMcReborn.LOGGER.debug("Registering static compatibility overlays before registry freeze");
            PolyMcReborn.runtime().ensureStaticPlanFrozen();
        }
    }

    public static void afterFreeze(Registry<?> registry) {
        if (registry == BuiltInRegistries.ITEM) {
            PolyMcReborn.runtime().finalizeBoundItemCarriers();
        }
    }
}
