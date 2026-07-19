/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import io.github.polymcreborn.api.DiagnosticCollector;
import io.github.polymcreborn.config.SafeGlob;

import java.util.List;
import java.util.Objects;

/** Immutable, versioned display policy. It never deletes an original diagnostic. */
public record DiagnosticPolicy(int schemaVersion, List<Rule> rules, String source) {
    public DiagnosticPolicy {
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Unsupported diagnostic policy schema_version " + schemaVersion);
        }
        rules = List.copyOf(rules);
        source = Objects.requireNonNull(source, "source");
    }

    public static DiagnosticPolicy empty(String source) {
        return new DiagnosticPolicy(1, List.of(), source);
    }

    public Applied apply(String code, String registryId, DiagnosticCollector.Severity severity) {
        return apply(code, DiagnosticContext.basic(registryId), severity);
    }

    public Applied apply(String code, DiagnosticContext context,
                         DiagnosticCollector.Severity severity) {
        for (Rule rule : rules) {
            if (rule.matches(code, context)) {
                DiagnosticCollector.Severity effective = rule.effectiveSeverity();
                if (protectedDiagnostic(code) && effective.ordinal() < severity.ordinal()) {
                    effective = severity;
                }
                return new Applied(effective, rule.id(), source, rule.reason(), rule.operatorNote(),
                        rule.knownIssue());
            }
        }
        return new Applied(severity, "none", source, "no matching policy rule", "", false);
    }

    static boolean protectedDiagnostic(String code) {
        String normalized = code.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("security.") || normalized.startsWith("fatal.")
                || normalized.contains("corrupt")
                || normalized.contains("signature") || normalized.contains("zip-slip")
                || normalized.contains("path-traversal") || normalized.contains("forgery")
                || normalized.contains("duplicat") || normalized.contains("decode")
                || normalized.contains("hmac") || normalized.contains("authentication");
    }

    public record Rule(String id, SafeGlob code, SafeGlob registry, SafeGlob modId,
                       SafeGlob contentType, SafeGlob providerId, SafeGlob adapterId,
                       SafeGlob mappingStatus, SafeGlob clientProfile, SafeGlob packStatus,
                       SafeGlob decisionId,
                       DiagnosticCollector.Severity effectiveSeverity,
                       String reason, String operatorNote, boolean knownIssue) {
        public Rule {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(modId, "modId");
            Objects.requireNonNull(contentType, "contentType");
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(adapterId, "adapterId");
            Objects.requireNonNull(mappingStatus, "mappingStatus");
            Objects.requireNonNull(clientProfile, "clientProfile");
            Objects.requireNonNull(packStatus, "packStatus");
            Objects.requireNonNull(decisionId, "decisionId");
            Objects.requireNonNull(effectiveSeverity, "effectiveSeverity");
            reason = Objects.requireNonNull(reason, "reason");
            operatorNote = Objects.requireNonNull(operatorNote, "operatorNote");
        }

        boolean matches(String diagnosticCode, DiagnosticContext context) {
            return code.matches(diagnosticCode) && registry.matches(context.registryId())
                    && modId.matches(context.modId()) && contentType.matches(context.contentType())
                    && providerId.matches(context.providerId()) && adapterId.matches(context.adapterId())
                    && mappingStatus.matches(context.mappingStatus())
                    && clientProfile.matches(context.clientProfile())
                    && packStatus.matches(context.packStatus())
                    && decisionId.matches(context.decisionId());
        }
    }

    public record Applied(DiagnosticCollector.Severity severity, String ruleId, String source,
                          String reason, String operatorNote, boolean knownIssue) {
    }
}
