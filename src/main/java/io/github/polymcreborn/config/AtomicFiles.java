/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Same-directory durable temporary write followed by an atomic replacement when supported. */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    public static void write(Path target, byte[] data) throws IOException {
        var absolute = target.toAbsolutePath().normalize();
        var parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Target has no parent directory: " + target);
        }
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, absolute.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                var buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Publishes a fully written same-directory temporary file without loading it into heap memory. */
    public static void replace(Path prepared, Path target) throws IOException {
        var source = prepared.toAbsolutePath().normalize();
        var absolute = target.toAbsolutePath().normalize();
        var parent = absolute.getParent();
        if (parent == null || !parent.equals(source.getParent())) {
            throw new IOException("Prepared file must be in the target directory");
        }
        try (var channel = FileChannel.open(source, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(source, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
