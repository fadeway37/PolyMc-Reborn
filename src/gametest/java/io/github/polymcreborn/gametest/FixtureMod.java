/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import net.fabricmc.api.ModInitializer;

/** Ensures test content is registered even when no legacy bridge is present. */
public final class FixtureMod implements ModInitializer {
    @Override
    public void onInitialize() {
        FixtureContent.bootstrap();
    }
}
