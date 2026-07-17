/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

/** Minimal diagnostic sink usable by extensions without depending on the internal reporter. */
@FunctionalInterface
public interface DiagnosticCollector {
    void record(String code, String registryId, String message, Severity severity);

    enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
