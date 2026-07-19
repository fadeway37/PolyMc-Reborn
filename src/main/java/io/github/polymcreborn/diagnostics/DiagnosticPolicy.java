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
        for (Rule rule : rules) {
            if (rule.code().matches(code) && rule.registry().matches(registryId)) {
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
        return normalized.startsWith("security.") || normalized.contains("corrupt")
                || normalized.contains("signature") || normalized.contains("zip-slip")
                || normalized.contains("path-traversal") || normalized.contains("forgery")
                || normalized.contains("duplication") || normalized.contains("decode-failure");
    }

    public record Rule(String id, SafeGlob code, SafeGlob registry,
                       DiagnosticCollector.Severity effectiveSeverity,
                       String reason, String operatorNote, boolean knownIssue) {
        public Rule {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(effectiveSeverity, "effectiveSeverity");
            reason = Objects.requireNonNull(reason, "reason");
            operatorNote = Objects.requireNonNull(operatorNote, "operatorNote");
        }
    }

    public record Applied(DiagnosticCollector.Severity severity, String ruleId, String source,
                          String reason, String operatorNote, boolean knownIssue) {
    }
}
