/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core.mixin;

import io.github.polymcreborn.backend.polymer.RecipeBookSanitizer;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

/** Removes only recipe displays that use server-only wire types. */
@Mixin(ClientboundRecipeBookAddPacket.class)
abstract class RecipeBookPacketMixin {
    @ModifyVariable(method = "<init>(Ljava/util/List;Z)V", at = @At("HEAD"),
            argsOnly = true, ordinal = 0)
    private static List<ClientboundRecipeBookAddPacket.Entry> polymcReborn$filterRecipeDisplays(
            List<ClientboundRecipeBookAddPacket.Entry> entries) {
        return RecipeBookSanitizer.filter(entries);
    }
}
