/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

import java.util.List;

/** Path-free build data suitable for diagnostics without leaking local filesystem details. */
public record PackBuildResult(
        String sha256,
        int entryCount,
        long uncompressedBytes,
        long archiveBytes,
        boolean cacheHit,
        List<String> duplicates,
        List<String> warnings) {
    public PackBuildResult {
        duplicates = List.copyOf(duplicates);
        warnings = List.copyOf(warnings);
    }
}
