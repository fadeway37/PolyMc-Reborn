/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

/** Applies an already-frozen decision. Public APIs deliberately expose no Polymer implementation types. */
public interface CompatibilityBackend {
    String id();

    boolean supports(ContentType contentType);

    void apply(MappingDecision decision, DiagnosticCollector diagnostics);
}
