/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Collects a mod root without following links and verifies containment for every resource. */
public final class SafeResourceCollector {
    private SafeResourceCollector() {
    }

    public static void collectAssets(Path root, String source, DeterministicResourcePack target) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path assets = normalizedRoot.resolve("assets").normalize();
        if (!assets.startsWith(normalizedRoot) || Files.notExists(assets)) {
            return;
        }
        try {
            Files.walkFileTree(assets, java.util.Set.of(), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    if (Files.isSymbolicLink(directory)) {
                        throw new IOException("Symbolic links are not accepted in resource roots");
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Path normalized = file.toAbsolutePath().normalize();
                    if (!normalized.startsWith(normalizedRoot) || Files.isSymbolicLink(file)
                            || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Resource escaped root: " + file.getFileName());
                    }
                    String relative = normalizedRoot.relativize(normalized).toString().replace('\\', '/');
                    target.putFile(relative, file, source);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException exception) {
            throw new ResourcePackException("Safe resource collection failed for " + source, exception);
        }
    }
}
