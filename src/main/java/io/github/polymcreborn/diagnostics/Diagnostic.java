/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import io.github.polymcreborn.api.DiagnosticCollector;

/** Stable path-free diagnostic with an immutable original/effective audit chain. */
public record Diagnostic(
        String diagnosticCode,
        String registryId,
        String modId,
        String contentType,
        String providerId,
        String adapterId,
        String mappingStatus,
        String clientProfile,
        String packStatus,
        String message,
        DiagnosticCollector.Severity originalSeverity,
        DiagnosticCollector.Severity effectiveSeverity,
        String policyRuleId,
        String policySource,
        String policyReason,
        String operatorNote,
        boolean knownIssue,
        String lifecycleStage,
        String decisionId) implements Comparable<Diagnostic> {

    public String code() {
        return diagnosticCode;
    }

    public DiagnosticCollector.Severity severity() {
        return effectiveSeverity;
    }

    @Override
    public int compareTo(Diagnostic other) {
        int byId = registryId.compareTo(other.registryId);
        if (byId != 0) {
            return byId;
        }
        int byCode = diagnosticCode.compareTo(other.diagnosticCode);
        return byCode != 0 ? byCode : message.compareTo(other.message);
    }
}
