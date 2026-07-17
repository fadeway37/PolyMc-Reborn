/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.polymcreborn.api.ClientProfile;
import io.github.polymcreborn.core.PolyMcReborn;
import io.github.polymcreborn.core.RebornRuntime;
import io.github.polymcreborn.diagnostics.CompatibilityStats;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;

/** Brigadier operator surface; no command mutates a frozen mapping plan. */
public final class RebornCommands {
    private RebornCommands() {
    }

    public static void register(RebornRuntime runtime) {
        CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
            var primary = dispatcher.register(root("polymcreborn", runtime));
            dispatcher.register(Commands.literal("pmcr").redirect(primary));
            if (dispatcher.getRoot().getChild("polymc") == null) {
                dispatcher.register(Commands.literal("polymc").redirect(primary));
            }
        });
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name, RebornRuntime runtime) {
        return Commands.literal(name)
                .then(Commands.literal("about").executes(context -> about(context, runtime)))
                .then(Commands.literal("scan").requires(RebornCommands::isAdmin)
                        .executes(context -> scan(context, runtime)))
                .then(Commands.literal("report").requires(RebornCommands::isAdmin)
                        .executes(context -> report(context, runtime, "json+markdown"))
                        .then(Commands.literal("json").executes(context -> report(context, runtime, "json")))
                        .then(Commands.literal("markdown").executes(context -> report(context, runtime, "markdown"))))
                .then(Commands.literal("why").requires(RebornCommands::isAdmin)
                        .then(Commands.argument("registry-id", StringArgumentType.greedyString())
                                .executes(context -> why(context, runtime,
                                        StringArgumentType.getString(context, "registry-id")))))
                .then(Commands.literal("pack").requires(RebornCommands::isAdmin)
                        .then(Commands.literal("build").executes(context -> packBuild(context, runtime))))
                .then(Commands.literal("config").requires(RebornCommands::isAdmin)
                        .then(Commands.literal("validate").executes(context -> configValidate(context, runtime))))
                .then(Commands.literal("stats").requires(RebornCommands::isAdmin)
                        .executes(context -> stats(context, runtime)))
                .then(Commands.literal("client-profile").requires(RebornCommands::isAdmin)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> clientProfile(context))));
    }

    private static boolean isAdmin(CommandSourceStack source) {
        return source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS));
    }

    private static int about(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        success(context, "PolyMc Reborn " + PolyMcReborn.VERSION + " for Minecraft 26.1.2; Polymer backend; "
                + "packet fallback=" + runtime.packetFallback().enabled());
        return 1;
    }

    private static int scan(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        var plan = runtime.plan();
        success(context, "Frozen plan contains " + plan.size()
                + " entries. Scan is read-only; registry mappings were not changed.");
        return plan.size();
    }

    private static int report(CommandContext<CommandSourceStack> context, RebornRuntime runtime, String requested) {
        runtime.writeReports(requested);
        success(context, "Compatibility report refreshed (" + requested
                + "). Changes to mapping-affecting config require a restart.");
        return 1;
    }

    private static int why(CommandContext<CommandSourceStack> context, RebornRuntime runtime, String registryId) {
        var decisions = runtime.plan().decisionsForRegistryId(registryId);
        if (decisions.isEmpty()) {
            context.getSource().sendFailure(Component.literal("No planned registry entry: " + registryId));
            return 0;
        }
        for (var decision : decisions) {
            success(context, decision.descriptor().contentType() + " " + registryId + " -> "
                    + decision.status() + " via " + decision.provider() + "/" + decision.backend()
                    + " strategy=" + decision.strategy() + " carrier=" + decision.clientCarrier());
            for (var candidate : runtime.plan().candidateTrace(decision.descriptor())) {
                success(context, "candidate " + candidate.provider() + ": "
                        + (candidate.matched() ? candidate.proposedStatus() : "not applicable")
                        + " - " + candidate.summary());
            }
            for (var reason : decision.reasonChain()) {
                success(context, "reason: " + reason);
            }
        }
        return decisions.size();
    }

    private static int packBuild(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        var result = runtime.packService().build();
        runtime.writePackReport(result);
        success(context, "Built polymc-reborn-resource-pack.zip: sha256=" + result.sha256()
                + ", entries=" + result.entryCount() + ", issues=" + !result.warnings().isEmpty());
        return result.warnings().isEmpty() ? 1 : 0;
    }

    private static int configValidate(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        runtime.configManager().validate();
        new io.github.polymcreborn.config.CompatProfileLoader().load(runtime.configManager().compatDirectory());
        success(context, "config.json and compat.d are valid. Mapping changes require a server restart.");
        return 1;
    }

    private static int stats(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        var stats = CompatibilityStats.from(runtime.plan());
        success(context, "native=" + stats.totals().get(io.github.polymcreborn.api.MappingStatus.NATIVE)
                + ", explicit=" + stats.totals().get(io.github.polymcreborn.api.MappingStatus.EXPLICIT)
                + ", legacy=" + stats.totals().get(io.github.polymcreborn.api.MappingStatus.LEGACY)
                + ", profile=" + stats.totals().get(io.github.polymcreborn.api.MappingStatus.PROFILE)
                + ", heuristic=" + stats.totals().get(io.github.polymcreborn.api.MappingStatus.HEURISTIC)
                + ", fallback=" + stats.totals().get(io.github.polymcreborn.api.MappingStatus.FALLBACK)
                + ", unsupported=" + stats.totals().get(io.github.polymcreborn.api.MappingStatus.UNSUPPORTED)
                + ", errors=" + stats.totals().get(io.github.polymcreborn.api.MappingStatus.ERROR)
                + ", item_cache="
                + io.github.polymcreborn.backend.polymer.ItemProjectionCacheStats.snapshot());
        return runtime.plan().size();
    }

    private static int clientProfile(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = EntityArgument.getPlayer(context, "player");
        success(context, player.getGameProfile().name() + " profile=" + ClientProfile.VANILLA
                + " (unknown clients always default to VANILLA)");
        return 1;
    }

    private static void success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }
}
