/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.polymcreborn.api.ClientProfile;
import io.github.polymcreborn.core.PolyMcReborn;
import io.github.polymcreborn.core.RebornRuntime;
import io.github.polymcreborn.diagnostics.CompatibilityStats;
import io.github.polymcreborn.mapping.MappingPlanDiff;
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
                .then(diagnosticCommands(runtime))
                .then(supportCommands(runtime))
                .then(mappingCommands("mappings", runtime))
                .then(mappingCommands("mapping", runtime))
                .then(Commands.literal("stats").requires(RebornCommands::isAdmin)
                        .executes(context -> stats(context, runtime)))
                .then(Commands.literal("client-profile").requires(RebornCommands::isAdmin)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(context -> clientProfile(context))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> diagnosticCommands(RebornRuntime runtime) {
        return Commands.literal("diagnostics").requires(RebornCommands::isAdmin)
                .then(Commands.literal("status").executes(context -> diagnosticsStatus(context, runtime)))
                .then(Commands.literal("validate").executes(context -> diagnosticsValidate(context, runtime)))
                .then(Commands.literal("why")
                        .then(Commands.argument("code", StringArgumentType.word())
                                .executes(context -> diagnosticsWhy(context, runtime,
                                        StringArgumentType.getString(context, "code")))))
                .then(Commands.literal("list")
                        .executes(context -> diagnosticsList(context, runtime, null))
                        .then(Commands.argument("mod-id", StringArgumentType.word())
                                .executes(context -> diagnosticsList(context, runtime,
                                        StringArgumentType.getString(context, "mod-id")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> supportCommands(RebornRuntime runtime) {
        return Commands.literal("support").requires(RebornCommands::isAdmin)
                .then(Commands.literal("bundle").executes(context -> supportBundle(context, runtime))
                        .then(Commands.literal("status")
                                .executes(context -> supportBundleStatus(context, runtime))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mappingCommands(String name, RebornRuntime runtime) {
        return Commands.literal(name).requires(RebornCommands::isAdmin)
                .then(Commands.literal("status").executes(context -> mappingStatus(context, runtime)))
                .then(Commands.literal("validate").executes(context -> mappingValidate(context, runtime)))
                .then(Commands.literal("diff").executes(context -> mappingDiff(context, runtime)))
                .then(Commands.literal("dry-run").executes(context -> mappingDryRun(context, runtime)))
                .then(Commands.literal("backup").executes(context -> mappingBackup(context, runtime)))
                .then(Commands.literal("rollback")
                        .then(Commands.argument("backup-id", StringArgumentType.word())
                                .executes(context -> mappingRollback(context, runtime,
                                        StringArgumentType.getString(context, "backup-id")))));
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

    private static int diagnosticsStatus(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        success(context, "Diagnostic policy schema=" + runtime.diagnostics().policy().schemaVersion()
                + ", rules=" + runtime.diagnostics().policy().rules().size()
                + ", records=" + runtime.diagnostics().snapshot().size()
                + ", dropped=" + runtime.diagnostics().droppedCount());
        return 1;
    }

    private static int diagnosticsValidate(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        new io.github.polymcreborn.diagnostics.DiagnosticPolicyLoader()
                .parse(runtime.configManager().diagnosticsPolicyFile());
        success(context, "diagnostics-policy.json is valid; display policy changes require restart");
        return 1;
    }

    private static int diagnosticsWhy(CommandContext<CommandSourceStack> context, RebornRuntime runtime,
                                      String code) {
        var records = runtime.diagnostics().snapshot().stream()
                .filter(diagnostic -> diagnostic.diagnosticCode().equals(code)).toList();
        records.forEach(diagnostic -> success(context, diagnostic.diagnosticCode() + " "
                + diagnostic.originalSeverity() + " -> " + diagnostic.effectiveSeverity()
                + " rule=" + diagnostic.policyRuleId() + " reason=" + diagnostic.policyReason()));
        return records.size();
    }

    private static int diagnosticsList(CommandContext<CommandSourceStack> context, RebornRuntime runtime,
                                       String modId) {
        var records = runtime.diagnostics().snapshot().stream()
                .filter(diagnostic -> modId == null || diagnostic.registryId().equals(modId)
                        || diagnostic.registryId().startsWith(modId + ":"))
                .limit(100).toList();
        records.forEach(diagnostic -> success(context, diagnostic.effectiveSeverity() + " "
                + diagnostic.diagnosticCode() + " " + diagnostic.registryId()));
        return records.size();
    }

    private static int supportBundle(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        var result = runtime.supportBundles().build();
        success(context, "Created local support bundle " + result.fileName() + ": sha256="
                + result.sha256() + ", bytes=" + result.sizeBytes() + ", entries=" + result.entryCount()
                + ", redactions=" + result.redactionCount() + "; no upload was performed");
        return 1;
    }

    private static int supportBundleStatus(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        var result = runtime.supportBundles().status();
        if (result == null) {
            success(context, "No support bundle has been generated in this server process");
            return 1;
        }
        success(context, result.fileName() + " sha256=" + result.sha256()
                + " bytes=" + result.sizeBytes() + " entries=" + result.entryCount());
        return 1;
    }

    private static int mappingStatus(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        try {
            var status = runtime.mappingBackups().validateCurrent();
            int backups = runtime.mappingBackups().listBackups().size();
            success(context, status.present()
                    ? "Mapping store is valid: entries=" + status.entryCount() + ", sha256=" + status.sha256()
                            + ", schema=" + status.schemaVersion() + ", algorithm=" + status.algorithmVersion()
                            + ", backups=" + backups
                    : "Mapping store is not present; the frozen in-memory plan remains active. backups=" + backups);
            return 1;
        } catch (RuntimeException exception) {
            return mappingFailure(context, exception);
        }
    }

    private static int mappingValidate(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        try {
            var status = runtime.mappingBackups().validateCurrent();
            if (!status.present()) {
                context.getSource().sendFailure(Component.literal("No mappings-v1.json exists to validate"));
                return 0;
            }
            success(context, "Strict mapping validation passed: entries=" + status.entryCount()
                    + ", sha256=" + status.sha256());
            return 1;
        } catch (RuntimeException exception) {
            return mappingFailure(context, exception);
        }
    }

    private static int mappingDiff(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        var diff = runtime.mappingDiff();
        success(context, "Startup mapping diff: " + formatDiffCounts(diff)
                + "; incompatible=" + diff.hasIncompatibleChanges());
        return diff.hasIncompatibleChanges() ? 0 : 1;
    }

    private static int mappingDryRun(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        try {
            var result = runtime.mappingDryRun();
            success(context, "Mapping dry-run completed without writing files or changing the frozen plan: "
                    + formatDiffCounts(result.diff()) + "; proposed_bytes=" + result.proposedSizeBytes()
                    + ", proposed_sha256=" + result.proposedSha256());
            return result.diff().hasIncompatibleChanges() ? 0 : 1;
        } catch (RuntimeException exception) {
            return mappingFailure(context, exception);
        }
    }

    private static int mappingBackup(CommandContext<CommandSourceStack> context, RebornRuntime runtime) {
        try {
            var backup = runtime.mappingBackups().backupCurrent("26.1.2", PolyMcReborn.VERSION);
            success(context, "Created validated mapping backup " + backup.id() + ": sha256=" + backup.sha256()
                    + ", bytes=" + backup.sizeBytes());
            return 1;
        } catch (RuntimeException exception) {
            return mappingFailure(context, exception);
        }
    }

    private static int mappingRollback(CommandContext<CommandSourceStack> context, RebornRuntime runtime,
                                       String backupId) {
        try {
            var result = runtime.mappingBackups().prepareRollback(backupId, "26.1.2", PolyMcReborn.VERSION);
            success(context, "Validated rollback " + result.sourceBackupId()
                    + " is staged in " + result.pendingFileName()
                    + "; it will activate before planning on the next server restart. The current frozen plan was not changed"
                    + (result.safetyBackupId() == null ? "." : "; safety backup=" + result.safetyBackupId()));
            return 1;
        } catch (RuntimeException exception) {
            return mappingFailure(context, exception);
        }
    }

    private static String formatDiffCounts(MappingPlanDiff diff) {
        var values = new java.util.ArrayList<String>();
        for (var kind : MappingPlanDiff.ChangeKind.values()) {
            values.add(kind.name().toLowerCase(java.util.Locale.ROOT) + "=" + diff.counts().get(kind));
        }
        return String.join(", ", values);
    }

    private static int mappingFailure(CommandContext<CommandSourceStack> context, RuntimeException exception) {
        context.getSource().sendFailure(Component.literal("Mapping operation failed safely: "
                + exception.getMessage()));
        return 0;
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
                + io.github.polymcreborn.backend.polymer.ItemProjectionCacheStats.snapshot()
                + ", gui_sessions=" + runtime.guiProjectionService().activeSessionCount()
                + ", gui_rejected=" + runtime.guiProjectionService().rejectedOpenCount()
                + ", gui_failures=" + runtime.guiProjectionService().failedOpenCount()
                + ", entity_sessions=" + runtime.entityProjectionBackend().activeSessionCount()
                + ", entity_interactions=" + runtime.entityProjectionBackend().acceptedInteractionCount()
                + ", entity_auth_rejected=" + runtime.entityProjectionBackend().authorizationRejectionCount()
                + ", entity_replays_rejected=" + runtime.entityProjectionBackend().replayRejectionCount()
                + ", entity_adapter_failures=" + runtime.entityProjectionBackend().adapterFailureCount()
                + ", entity_session_failures=" + runtime.entityProjectionBackend().sessionFailureCount());
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
