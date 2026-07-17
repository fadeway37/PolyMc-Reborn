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
        if (registry != BuiltInRegistries.ITEM && registry != BuiltInRegistries.BLOCK
                && registry != BuiltInRegistries.ENTITY_TYPE && registry != BuiltInRegistries.MENU) {
            return;
        }
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
