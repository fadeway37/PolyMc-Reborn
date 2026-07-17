/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

/** Stable provider precedence. Native remains first unless the dangerous override is enabled. */
public enum ProviderTier {
    NATIVE,
    ADMIN_FORCE,
    EXPLICIT,
    LEGACY,
    PROFILE,
    HEURISTIC,
    FALLBACK,
    UNSUPPORTED
}
