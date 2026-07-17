/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

/** Adds deterministic resource bytes through a backend-neutral, path-validated sink. */
@FunctionalInterface
public interface ResourceContributor {
    void contribute(ResourceSink sink) throws Exception;

    interface ResourceSink {
        void put(String relativePath, byte[] data, String source);
    }
}
