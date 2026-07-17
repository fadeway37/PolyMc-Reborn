/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import io.github.polymcreborn.api.DiagnosticCollector;

import java.util.ArrayList;
import java.util.List;

/** Bounded initialization-time sink; packet/tick hot paths never perform filesystem I/O. */
public final class BoundedDiagnosticCollector implements DiagnosticCollector {
    private final int capacity;
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private long dropped;

    public BoundedDiagnosticCollector(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    @Override
    public synchronized void record(String code, String registryId, String message, Severity severity) {
        if (diagnostics.size() == capacity) {
            dropped++;
            return;
        }
        diagnostics.add(new Diagnostic(code, registryId, message, severity));
    }

    public synchronized List<Diagnostic> snapshot() {
        return diagnostics.stream().sorted().toList();
    }

    public synchronized long droppedCount() {
        return dropped;
    }
}
