/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import io.github.polymcreborn.config.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Builds the active Polymer pack into a temporary file, then publishes it atomically. */
public final class PolymerPackService {
    private final Path cacheDirectory;
    private final Path output;
    private final long maxCacheBytes;

    public PolymerPackService(Path cacheDirectory) {
        this(cacheDirectory, Long.MAX_VALUE);
    }

    public PolymerPackService(Path cacheDirectory, long maxCacheBytes) {
        this.cacheDirectory = cacheDirectory;
        this.output = cacheDirectory.resolve("polymc-reborn-resource-pack.zip");
        this.maxCacheBytes = maxCacheBytes;
    }

    public PackBuildResult build() {
        Path temporary = null;
        try {
            Files.createDirectories(cacheDirectory);
            temporary = Files.createTempFile(cacheDirectory, "polymer-pack.", ".tmp.zip");
            var result = PolymerResourcePackUtils.getInstance().build(temporary);
            long archiveBytes = Files.size(temporary);
            if (archiveBytes > maxCacheBytes) {
                throw new ResourcePackException("Generated pack exceeds cache_limits.max_bytes=" + maxCacheBytes);
            }
            int entries;
            long uncompressedBytes;
            try (var zip = new java.util.zip.ZipFile(temporary.toFile())) {
                entries = zip.size();
                uncompressedBytes = zip.stream().mapToLong(entry -> Math.max(0, entry.getSize())).sum();
            }
            String sha256 = sha256(temporary);
            boolean cacheHit = Files.isRegularFile(output) && Files.size(output) == archiveBytes
                    && Files.mismatch(output, temporary) == -1;
            if (!cacheHit) {
                AtomicFiles.replace(temporary, output);
                temporary = null;
            }
            return new PackBuildResult(sha256, entries, uncompressedBytes,
                    archiveBytes, cacheHit, List.of(), result.hadIssues()
                    ? List.of("Polymer reported resource-pack generation issues") : List.of());
        } catch (IOException | ExecutionException exception) {
            throw new ResourcePackException("Polymer resource-pack build failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResourcePackException("Polymer resource-pack build was interrupted", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The final output is already independent; a stale temp is safe and visible in cache.
                }
            }
        }
    }

    public Path output() {
        return output;
    }

    private static String sha256(Path path) throws IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                var buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM has no SHA-256", impossible);
        }
    }
}
