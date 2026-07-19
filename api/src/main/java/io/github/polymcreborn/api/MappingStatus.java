/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

/** Explainable outcome of one compatibility decision. */
public enum MappingStatus {
    NATIVE,
    EXPLICIT,
    LEGACY,
    PROFILE,
    HEURISTIC,
    FALLBACK,
    UNSUPPORTED,
    ERROR
}
