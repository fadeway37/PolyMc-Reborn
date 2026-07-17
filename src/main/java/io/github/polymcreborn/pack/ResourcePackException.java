/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

/** Safe resource collection or deterministic archive construction failure. */
public final class ResourcePackException extends RuntimeException {
    public ResourcePackException(String message) {
        super(message);
    }

    public ResourcePackException(String message, Throwable cause) {
        super(message, cause);
    }
}
