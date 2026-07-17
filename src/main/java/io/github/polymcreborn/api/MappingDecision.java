/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

import java.util.List;
import java.util.Objects;

/** Complete, immutable explanation of the selected client projection. */
public record MappingDecision(
        ContentDescriptor descriptor,
        MappingStatus status,
        String provider,
        String backend,
        String strategy,
        String clientCarrier,
        double confidence,
        int degradation,
        List<String> reasonChain,
        List<String> resourceDependencies,
        List<String> warnings,
        String failureReason) {

    public MappingDecision {
        descriptor = Objects.requireNonNull(descriptor, "descriptor");
        status = Objects.requireNonNull(status, "status");
        provider = requireText(provider, "provider");
        backend = requireText(backend, "backend");
        strategy = requireText(strategy, "strategy");
        clientCarrier = Objects.requireNonNullElse(clientCarrier, "");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        if (degradation < 0 || degradation > 100) {
            throw new IllegalArgumentException("degradation must be between 0 and 100");
        }
        reasonChain = List.copyOf(reasonChain);
        resourceDependencies = resourceDependencies.stream().sorted().distinct().toList();
        warnings = warnings.stream().sorted().distinct().toList();
        if ((status == MappingStatus.ERROR || status == MappingStatus.UNSUPPORTED)
                && (failureReason == null || failureReason.isBlank())) {
            throw new IllegalArgumentException(status + " decisions require a failure reason");
        }
    }

    public MappingDecision withProvider(String selectedProvider, MappingStatus selectedStatus) {
        return new MappingDecision(descriptor, selectedStatus, selectedProvider, backend, strategy, clientCarrier,
                confidence, degradation, reasonChain, resourceDependencies, warnings, failureReason);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
