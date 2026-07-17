/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import io.github.polymcreborn.api.DiagnosticCollector;

/** Stable path-free diagnostic message. */
public record Diagnostic(String code, String registryId, String message,
                         DiagnosticCollector.Severity severity) implements Comparable<Diagnostic> {
    @Override
    public int compareTo(Diagnostic other) {
        int byId = registryId.compareTo(other.registryId);
        if (byId != 0) {
            return byId;
        }
        int byCode = code.compareTo(other.code);
        return byCode != 0 ? byCode : message.compareTo(other.message);
    }
}
