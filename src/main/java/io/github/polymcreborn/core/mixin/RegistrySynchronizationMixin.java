/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.polymcreborn.backend.polymer.DynamicRegistrySanitizer;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;
import java.util.function.BiConsumer;

/** Filters only dynamic registry entries that depend on server-only static ids. */
@Mixin(RegistrySynchronization.class)
abstract class RegistrySynchronizationMixin {
    @SuppressWarnings({"rawtypes", "unchecked"})
    @WrapOperation(method = "lambda$packRegistry$0",
            at = @At(value = "INVOKE",
                    target = "Ljava/util/function/BiConsumer;accept(Ljava/lang/Object;Ljava/lang/Object;)V"))
    private static void polymcReborn$filterServerOnlyDynamicEntries(
            BiConsumer instance, Object registry, Object entries, Operation<Void> original) {
        var filtered = DynamicRegistrySanitizer.filter((ResourceKey<?>) registry,
                (List<RegistrySynchronization.PackedRegistryEntry>) entries);
        original.call(instance, registry, filtered);
    }
}
