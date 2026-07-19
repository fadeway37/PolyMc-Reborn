/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import io.github.polymcreborn.api.DiagnosticCollector;
import io.github.polymcreborn.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiagnosticPolicyLoaderTest {
    @TempDir
    Path directory;

    @Test
    void appliesRuleAndRetainsOriginalSeverity() throws Exception {
        Path file = directory.resolve("diagnostics-policy.json");
        Files.writeString(file, """
                {
                  "schema_version": 1,
                  "rules": [{
                    "id": "promote-resource",
                    "code": "resource.*",
                    "registry_id": "*",
                    "effective_severity": "ERROR",
                    "reason": "operator release gate",
                    "operator_note": "fix before deployment",
                    "known_issue": true
                  }]
                }
                """);
        var policy = new DiagnosticPolicyLoader().parse(file);
        var collector = new BoundedDiagnosticCollector(4, policy);
        collector.record("resource.missing", "example:item", "missing model",
                DiagnosticCollector.Severity.INFO);

        Diagnostic diagnostic = collector.snapshot().getFirst();
        assertEquals(DiagnosticCollector.Severity.INFO, diagnostic.originalSeverity());
        assertEquals(DiagnosticCollector.Severity.ERROR, diagnostic.effectiveSeverity());
        assertEquals("promote-resource", diagnostic.policyRuleId());
    }

    @Test
    void protectedSecurityDiagnosticCannotBeDowngraded() throws Exception {
        Path file = directory.resolve("diagnostics-policy.json");
        Files.writeString(file, """
                {"schema_version":1,"rules":[{"id":"bad","code":"security.*",
                "registry_id":"*","effective_severity":"INFO","reason":"bad",
                "operator_note":"","known_issue":false}]}
                """);
        var policy = new DiagnosticPolicyLoader().parse(file);
        assertEquals(DiagnosticCollector.Severity.ERROR,
                policy.apply("security.signature", "example:item",
                        DiagnosticCollector.Severity.ERROR).severity());
    }

    @Test
    void matchesStructuredPackAndMappingContext() throws Exception {
        Path file = directory.resolve("diagnostics-policy.json");
        Files.writeString(file, """
                {"schema_version":1,"rules":[{"id":"declined-pack","code":"resource-pack.*",
                "registry_id":"client-*","mod_id":"*","content_type":"item",
                "provider_id":"adapter:*","adapter_id":"*","mapping_status":"explicit",
                "client_profile":"vanilla","pack_status":"declined","decision_id":"item:*",
                "effective_severity":"WARNING","reason":"safe degradation",
                "operator_note":"expected for this group","known_issue":true}]}
                """);
        var policy = new DiagnosticPolicyLoader().parse(file);
        var context = new DiagnosticContext("client-session", "example", "ITEM", "adapter:example",
                "", "EXPLICIT", "VANILLA", "DECLINED", "item:example:test", "network-session");

        assertEquals(DiagnosticCollector.Severity.WARNING,
                policy.apply("resource-pack.state", context,
                        DiagnosticCollector.Severity.INFO).severity());
    }

    @Test
    void rejectsRegexCharactersInsteadOfExecutingThem() throws Exception {
        Path file = directory.resolve("diagnostics-policy.json");
        Files.writeString(file, """
                {"schema_version":1,"rules":[{"id":"regex","code":"(a+)+",
                "registry_id":"*","effective_severity":"INFO","reason":"bad",
                "operator_note":"","known_issue":false}]}
                """);
        assertThrows(ConfigurationException.class, () -> new DiagnosticPolicyLoader().parse(file));
    }
}
