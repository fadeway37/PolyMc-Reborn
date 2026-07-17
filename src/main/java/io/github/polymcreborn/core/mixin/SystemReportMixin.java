/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core.mixin;

import io.github.polymcreborn.core.PolyMcReborn;
import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds one path-free diagnostic line without changing crash handling. */
@Mixin(SystemReport.class)
abstract class SystemReportMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void polymcReborn$addSystemDetail(CallbackInfo callback) {
        ((SystemReport) (Object) this).setDetail("PolyMc Reborn", PolyMcReborn::crashReportSummary);
    }
}
