/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import java.nio.file.Path;

/** A corrupt or incompatible mapping store is fatal; data is never silently discarded. */
public final class MappingStoreException extends RuntimeException {
    public MappingStoreException(Path path, String message) {
        super(path + ": " + message);
    }

    public MappingStoreException(Path path, String message, Throwable cause) {
        super(path + ": " + message, cause);
    }
}
