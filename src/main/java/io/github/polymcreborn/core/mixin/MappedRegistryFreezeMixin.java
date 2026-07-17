/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core.mixin;

import io.github.polymcreborn.core.RegistryFreezeHook;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One narrow lifecycle injection; it does not inspect or suppress packets. */
@Mixin(MappedRegistry.class)
abstract class MappedRegistryFreezeMixin<T> {
    @Inject(method = "freeze", at = @At("HEAD"))
    private void polymcReborn$beforeFreeze(CallbackInfoReturnable<Registry<T>> callback) {
        RegistryFreezeHook.beforeFreeze((Registry<?>) (Object) this);
    }

    @Inject(method = "freeze", at = @At("TAIL"))
    private void polymcReborn$afterFreeze(CallbackInfoReturnable<Registry<T>> callback) {
        RegistryFreezeHook.afterFreeze((Registry<?>) (Object) this);
    }
}
