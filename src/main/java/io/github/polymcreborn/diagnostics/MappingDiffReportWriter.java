/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import com.google.gson.GsonBuilder;
import io.github.polymcreborn.config.AtomicFiles;
import io.github.polymcreborn.core.PolyMcReborn;
import io.github.polymcreborn.mapping.MappingPlanDiff;
import io.github.polymcreborn.mapping.StoredMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** Writes a path-free, stable startup mapping-difference report. */
public final class MappingDiffReportWriter {
    public void write(Path reportsDirectory, MappingPlanDiff diff) {
        var root = new LinkedHashMap<String, Object>();
        root.put("schema_version", 1);
        root.put("project_version", PolyMcReborn.VERSION);
        root.put("minecraft_version", "26.1.2");
        root.put("incompatible_changes", diff.hasIncompatibleChanges());
        root.put("counts", diff.counts());
        var entries = new ArrayList<LinkedHashMap<String, Object>>();
        for (var entry : diff.entries()) {
            var record = new LinkedHashMap<String, Object>();
            record.put("key", entry.key());
            record.put("changes", entry.changes());
            record.put("previous", mapping(entry.previous()));
            record.put("proposed", mapping(entry.proposed()));
            entries.add(record);
        }
        root.put("entries", entries);
        byte[] json = (new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n")
                .getBytes(StandardCharsets.UTF_8);

        var markdown = new StringBuilder("# PolyMc Reborn mapping diff\n\n")
                .append("Project: `").append(PolyMcReborn.VERSION)
                .append("`; Minecraft: `26.1.2`.\n\n")
                .append("Incompatible changes: **").append(diff.hasIncompatibleChanges()).append("**\n\n")
                .append("## Counts\n\n| Change | Count |\n|---|---:|\n");
        diff.counts().forEach((kind, count) -> markdown.append("| ").append(kind)
                .append(" | ").append(count).append(" |\n"));
        markdown.append("\n## Entries\n\n");
        for (var entry : diff.entries()) {
            markdown.append("- `").append(entry.key()).append("`: ")
                    .append(entry.changes()).append("\n");
        }
        try {
            AtomicFiles.write(reportsDirectory.resolve("mapping-diff-latest.json"), json);
            AtomicFiles.write(reportsDirectory.resolve("mapping-diff-latest.md"),
                    markdown.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to atomically write mapping diff reports", exception);
        }
    }

    private static Object mapping(StoredMapping mapping) {
        if (mapping == null) {
            return null;
        }
        var value = new LinkedHashMap<String, Object>();
        value.put("registry_id", mapping.registryId());
        value.put("content_type", mapping.contentType());
        value.put("state", mapping.state());
        value.put("strategy", mapping.strategy());
        value.put("client_carrier", mapping.clientCarrier());
        value.put("resource_hash", mapping.resourceHash());
        value.put("created_with", mapping.createdWith());
        value.put("last_validated_with", mapping.lastValidatedWith());
        return value;
    }
}
