/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.mapping.MappingPlan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/** Deterministically resolves every descriptor and preserves every provider candidate in the trace. */
public final class MappingPlanner {
    public MappingPlan plan(Collection<ContentDescriptor> input, CompatibilityRegistry registry,
                            MappingContext context) {
        var providers = registry.freezeAndOrder(context);
        var descriptors = input.stream().sorted().toList();
        var decisions = new ArrayList<MappingDecision>(descriptors.size());
        var traces = new LinkedHashMap<String, List<CandidateTrace>>();

        for (var descriptor : descriptors) {
            MappingDecision selected = null;
            var candidateTraces = new ArrayList<CandidateTrace>();
            for (var provider : providers) {
                try {
                    var proposal = provider.evaluate(descriptor, context);
                    if (proposal.isPresent()) {
                        var decision = proposal.get();
                        if (!decision.descriptor().equals(descriptor)) {
                            throw new IllegalArgumentException("Provider returned a decision for another descriptor");
                        }
                        candidateTraces.add(new CandidateTrace(provider.id(), true, decision.status(),
                                String.join("; ", decision.reasonChain())));
                        if (selected == null) {
                            selected = decision;
                        }
                    } else {
                        candidateTraces.add(new CandidateTrace(provider.id(), false, null,
                                provider.explainNotApplicable(descriptor, context)));
                    }
                } catch (RuntimeException exception) {
                    candidateTraces.add(new CandidateTrace(provider.id(), true, MappingStatus.ERROR,
                            exception.getClass().getSimpleName() + ": " + exception.getMessage()));
                    if (selected == null) {
                        selected = new MappingDecision(descriptor, MappingStatus.ERROR, provider.id(), "none",
                                "provider-error", "", 0, 100,
                                List.of("Provider failed during deterministic planning"), List.of(), List.of(),
                                exception.getClass().getSimpleName() + ": " + exception.getMessage());
                    }
                }
            }
            if (selected == null) {
                selected = new MappingDecision(descriptor, MappingStatus.UNSUPPORTED, "planner", "none",
                        "unsupported", "", 1, 100,
                        List.of("No compatibility provider accepted this content"), List.of(), List.of(),
                        "No provider produced a safe mapping");
            }
            decisions.add(selected);
            traces.put(descriptor.key(), List.copyOf(candidateTraces));
        }
        return new MappingPlan(decisions, traces);
    }
}
