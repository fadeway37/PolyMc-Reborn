/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

/** Structured, path-free policy match context; empty values are explicit unknowns. */
public record DiagnosticContext(String registryId, String modId, String contentType,
                                String providerId, String adapterId, String mappingStatus,
                                String clientProfile, String packStatus, String decisionId,
                                String lifecycleStage) {
    public DiagnosticContext {
        registryId = normalize(registryId);
        modId = normalize(modId);
        contentType = normalize(contentType);
        providerId = normalize(providerId);
        adapterId = normalize(adapterId);
        mappingStatus = normalize(mappingStatus);
        clientProfile = normalize(clientProfile);
        packStatus = normalize(packStatus);
        decisionId = normalize(decisionId);
        lifecycleStage = normalize(lifecycleStage);
    }

    public static DiagnosticContext basic(String registryId) {
        String normalized = normalize(registryId);
        int separator = normalized.indexOf(':');
        String modId = separator > 0 ? normalized.substring(0, separator) : "";
        return new DiagnosticContext(normalized, modId, "", "", "", "", "", "", "",
                "initialization");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
