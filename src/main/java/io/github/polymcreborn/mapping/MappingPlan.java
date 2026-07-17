/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.compat.CandidateTrace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable final plan with O(1) hot-path lookup and separately stable iteration. */
public final class MappingPlan {
    private final Map<String, MappingDecision> decisionsByKey;
    private final Map<String, List<CandidateTrace>> tracesByKey;
    private final List<MappingDecision> orderedDecisions;

    public MappingPlan(List<MappingDecision> decisions, Map<String, List<CandidateTrace>> traces) {
        var ordered = new ArrayList<>(decisions);
        ordered.sort((left, right) -> left.descriptor().compareTo(right.descriptor()));
        var decisionMap = new HashMap<String, MappingDecision>();
        for (var decision : ordered) {
            var previous = decisionMap.put(decision.descriptor().key(), decision);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate mapping decision: " + decision.descriptor().key());
            }
        }
        var traceMap = new HashMap<String, List<CandidateTrace>>();
        traces.forEach((key, value) -> traceMap.put(key, List.copyOf(value)));
        this.decisionsByKey = Map.copyOf(decisionMap);
        this.tracesByKey = Map.copyOf(traceMap);
        this.orderedDecisions = List.copyOf(ordered);
    }

    public MappingDecision decision(ContentDescriptor descriptor) {
        return decisionsByKey.get(Objects.requireNonNull(descriptor, "descriptor").key());
    }

    public List<MappingDecision> decisionsForRegistryId(String registryId) {
        return orderedDecisions.stream()
                .filter(decision -> decision.descriptor().registryId().equals(registryId))
                .toList();
    }

    public MappingDecision decision(ContentType type, String registryId) {
        return decisionsByKey.get(type.name().toLowerCase(java.util.Locale.ROOT) + ":" + registryId);
    }

    public List<CandidateTrace> candidateTrace(ContentDescriptor descriptor) {
        return tracesByKey.getOrDefault(descriptor.key(), List.of());
    }

    public List<MappingDecision> orderedDecisions() {
        return orderedDecisions;
    }

    public int size() {
        return orderedDecisions.size();
    }

    public MappingPlan replaceDecisions(List<MappingDecision> decisions) {
        return new MappingPlan(decisions, tracesByKey);
    }
}
