/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ItemSemanticClassificationTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void recognizesConservativeVanillaSemanticRepresentatives() {
        assertEquals("material", MinecraftContentScanner.classifyItem(Items.APPLE),
                "component-only semantics are deliberately deferred until components bind");
        assertEquals("shield", MinecraftContentScanner.classifyItem(Items.SHIELD));
        assertEquals("bow", MinecraftContentScanner.classifyItem(Items.BOW));
        assertEquals("crossbow", MinecraftContentScanner.classifyItem(Items.CROSSBOW));
        assertEquals("throwable", MinecraftContentScanner.classifyItem(Items.SNOWBALL));
        assertEquals("throwable", MinecraftContentScanner.classifyItem(Items.ENDER_PEARL));
    }
}
