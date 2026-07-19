/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import com.google.gson.GsonBuilder;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.core.PolyMcReborn;
import io.github.polymcreborn.mapping.MappingPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Deterministic JSON and Markdown reports containing decisions and full reason chains. */
public final class CompatibilityReportWriter {
    public void write(Path reportsDirectory, MappingPlan plan, BoundedDiagnosticCollector diagnostics) {
        write(reportsDirectory, plan, diagnostics, List.of("json", "markdown"));
    }

    public void write(Path reportsDirectory, MappingPlan plan, BoundedDiagnosticCollector diagnostics,
                      List<String> formats) {
        var stats = CompatibilityStats.from(plan);
        var root = new LinkedHashMap<String, Object>();
        root.put("schema_version", 1);
        root.put("project_version", PolyMcReborn.VERSION);
        root.put("minecraft_version", "26.1.2");
        root.put("totals", stats.totals());
        root.put("by_mod", stats.byMod());
        root.put("item_projection_cache",
                io.github.polymcreborn.backend.polymer.ItemProjectionCacheStats.snapshot());
        var decisionRecords = new ArrayList<LinkedHashMap<String, Object>>();
        for (var decision : plan.orderedDecisions()) {
            var record = new LinkedHashMap<String, Object>();
            record.put("registry_id", decision.descriptor().registryId());
            record.put("owner_mod", decision.descriptor().ownerMod());
            record.put("content_type", decision.descriptor().contentType());
            record.put("status", decision.status());
            record.put("provider", decision.provider());
            record.put("backend", decision.backend());
            record.put("strategy", decision.strategy());
            record.put("client_carrier", decision.clientCarrier());
            record.put("confidence", decision.confidence());
            record.put("degradation", decision.degradation());
            record.put("reason_chain", decision.reasonChain());
            record.put("resource_dependencies", decision.resourceDependencies());
            record.put("warnings", decision.warnings());
            record.put("failure_reason", decision.failureReason());
            record.put("candidates", plan.candidateTrace(decision.descriptor()));
            decisionRecords.add(record);
        }
        root.put("decisions", decisionRecords);
        root.put("diagnostics", diagnostics.snapshot());
        root.put("dropped_diagnostics", diagnostics.droppedCount());
        var json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";

        var markdown = new StringBuilder("# PolyMc Reborn compatibility report\n\n")
                .append("Minecraft 26.1.2; PolyMc Reborn ").append(PolyMcReborn.VERSION).append(".\n\n")
                .append("## Summary\n\n")
                .append("| Status | Count |\n|---|---:|\n");
        stats.totals().forEach((status, count) -> markdown.append("| ").append(status)
                .append(" | ").append(count).append(" |\n"));
        markdown.append("\n## Decisions\n\n");
        for (var decision : plan.orderedDecisions()) {
            markdown.append("### `").append(decision.descriptor().contentType().name().toLowerCase())
                    .append(":").append(decision.descriptor().registryId()).append("`\n\n")
                    .append("- Status: ").append(decision.status()).append("\n")
                    .append("- Provider: `").append(decision.provider()).append("`\n")
                    .append("- Backend: `").append(decision.backend()).append("`\n")
                    .append("- Strategy: `").append(decision.strategy()).append("`\n")
                    .append("- Client carrier/floor: `").append(decision.clientCarrier()).append("`\n")
                    .append("- Confidence: ").append(decision.confidence()).append("\n")
                    .append("- Degradation: ").append(decision.degradation()).append("\n")
                    .append("- Candidates:\n");
            plan.candidateTrace(decision.descriptor()).forEach(candidate -> markdown.append("  - `")
                    .append(candidate.provider()).append("`: ")
                    .append(candidate.matched() ? candidate.proposedStatus() : "not applicable")
                    .append(" - ").append(candidate.summary()).append("\n"));
            markdown.append("- Resources:\n");
            if (decision.resourceDependencies().isEmpty()) {
                markdown.append("  - none\n");
            } else {
                decision.resourceDependencies().forEach(resource -> markdown.append("  - `")
                        .append(resource).append("`\n"));
            }
            markdown.append("- Warnings:\n");
            if (decision.warnings().isEmpty()) {
                markdown.append("  - none\n");
            } else {
                decision.warnings().forEach(warning -> markdown.append("  - ").append(warning).append("\n"));
            }
            markdown.append("- Reasons:\n");
            decision.reasonChain().forEach(reason -> markdown.append("  - ").append(reason).append("\n"));
            if (decision.failureReason() != null) {
                markdown.append("- Failure: ").append(decision.failureReason()).append("\n");
            }
            markdown.append("\n");
        }
        markdown.append("## Diagnostics\n\n")
                .append("| Code | Registry | Original | Effective | Policy rule |\n")
                .append("|---|---|---|---|---|\n");
        diagnostics.snapshot().forEach(diagnostic -> markdown.append("| `")
                .append(diagnostic.diagnosticCode()).append("` | `")
                .append(diagnostic.registryId()).append("` | ")
                .append(diagnostic.originalSeverity()).append(" | ")
                .append(diagnostic.effectiveSeverity()).append(" | `")
                .append(diagnostic.policyRuleId()).append("` |\n"));
        try {
            if (formats.contains("json")) {
                AtomicFiles.write(reportsDirectory.resolve("compatibility-latest.json"),
                        json.getBytes(StandardCharsets.UTF_8));
            }
            if (formats.contains("markdown")) {
                AtomicFiles.write(reportsDirectory.resolve("compatibility-latest.md"),
                        markdown.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to atomically write compatibility reports", exception);
        }
    }
}
