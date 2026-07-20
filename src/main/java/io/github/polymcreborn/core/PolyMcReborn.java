/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server entrypoint for PolyMc Reborn. */
public final class PolyMcReborn implements ModInitializer {
    public static final String MOD_ID = "polymc-reborn";
    public static final String VERSION = "0.4.0-rc.1+26.1.2";
    public static final Logger LOGGER = LoggerFactory.getLogger("PolyMc Reborn");
    private static RebornRuntime runtime;

    @Override
    public void onInitialize() {
        var conflicting = FabricLoader.getInstance().getAllMods().stream()
                .filter(container -> "polymc".equals(container.getMetadata().getId()))
                .findFirst();
        if (conflicting.isPresent()) {
            throw new IllegalStateException("PolyMc Reborn cannot run beside an independent mod whose actual id is 'polymc': "
                    + conflicting.get().getOrigin().getKind());
        }

        LOGGER.info("PolyMc Reborn {} initializing for Minecraft 26.1.2", VERSION);
        runtime = new RebornRuntime();
    }

    public static RebornRuntime runtime() {
        if (runtime == null) {
            throw new IllegalStateException("PolyMc Reborn has not initialized yet");
        }
        return runtime;
    }

    /** Path-free summary safe to evaluate lazily while a crash report is rendered. */
    public static String crashReportSummary() {
        var current = runtime;
        return current == null
                ? VERSION + "; initialization not complete"
                : current.crashReportSummary();
    }
}
