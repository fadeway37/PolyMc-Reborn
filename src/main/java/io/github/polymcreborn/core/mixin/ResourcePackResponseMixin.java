/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core.mixin;

import io.github.polymcreborn.core.PolyMcReborn;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes vanilla resource-pack acknowledgements without changing protocol behavior. */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ResourcePackResponseMixin {
    @Inject(method = "handleResourcePackResponse", at = @At("HEAD"))
    private void polymcReborn$observeResourcePackResponse(ServerboundResourcePackPacket packet,
                                                           CallbackInfo callback) {
        Object listener = this;
        if (listener instanceof ServerGamePacketListenerImpl game) {
            PolyMcReborn.runtime().playerPackStates().response(game.player.getUUID(), packet.action());
        }
    }
}
