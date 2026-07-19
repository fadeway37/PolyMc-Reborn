/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core.mixin;

import io.github.polymcreborn.core.PolyMcReborn;
import io.github.polymcreborn.api.DiagnosticCollector;
import io.github.polymcreborn.diagnostics.DiagnosticContext;
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
            var runtime = PolyMcReborn.runtime();
            var state = runtime.playerPackStates().response(game.player.getUUID(), packet.action());
            runtime.diagnostics().record("resource-pack.state", new DiagnosticContext(
                            "client-session", "", "", "", "", "", "VANILLA",
                            state.name(), "", "network-session"),
                    "Vanilla resource-pack response changed the session state to " + state,
                    state == io.github.polymcreborn.pack.PlayerPackStateService.State.FAILED
                            || state == io.github.polymcreborn.pack.PlayerPackStateService.State.REQUIRED_REJECTED
                            ? DiagnosticCollector.Severity.WARNING : DiagnosticCollector.Severity.INFO);
        }
    }
}
