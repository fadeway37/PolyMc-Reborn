/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

/** Explicitly reports that a finite Polymer carrier pool cannot accept another allocation. */
public final class MappingCapacityException extends RuntimeException {
    public MappingCapacityException(String registryId, String pool, int remaining) {
        super("Mapping capacity exhausted for " + registryId + " in " + pool + " (remaining=" + remaining + ")");
    }
}
