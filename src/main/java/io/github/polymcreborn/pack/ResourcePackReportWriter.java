/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

import com.google.gson.GsonBuilder;
import io.github.polymcreborn.config.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Writes path-free latest resource-pack reports in both operator formats. */
public final class ResourcePackReportWriter {
    public void write(Path reportsDirectory, PackBuildResult result) {
        var json = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(result) + "\n";
        var markdown = new StringBuilder()
                .append("# PolyMc Reborn resource pack report\n\n")
                .append("- SHA-256: `").append(result.sha256()).append("`\n")
                .append("- Entries: ").append(result.entryCount()).append("\n")
                .append("- Uncompressed bytes: ").append(result.uncompressedBytes()).append("\n")
                .append("- Archive bytes: ").append(result.archiveBytes()).append("\n")
                .append("- Cache hit: ").append(result.cacheHit()).append("\n\n")
                .append("## Warnings\n\n");
        if (result.warnings().isEmpty()) {
            markdown.append("None.\n");
        } else {
            result.warnings().forEach(warning -> markdown.append("- ").append(warning).append("\n"));
        }
        try {
            AtomicFiles.write(reportsDirectory.resolve("resource-pack-latest.json"),
                    json.getBytes(StandardCharsets.UTF_8));
            AtomicFiles.write(reportsDirectory.resolve("resource-pack-latest.md"),
                    markdown.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new ResourcePackException("Unable to atomically write resource pack reports", exception);
        }
    }
}
