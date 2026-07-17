/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.mapping.MappingPlan;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

/** Immutable global and per-mod compatibility counts. */
public record CompatibilityStats(Map<MappingStatus, Integer> totals,
                                 Map<String, Map<MappingStatus, Integer>> byMod) {
    public static CompatibilityStats from(MappingPlan plan) {
        var totals = new EnumMap<MappingStatus, Integer>(MappingStatus.class);
        var byMod = new TreeMap<String, Map<MappingStatus, Integer>>();
        for (var status : MappingStatus.values()) {
            totals.put(status, 0);
        }
        for (var decision : plan.orderedDecisions()) {
            totals.merge(decision.status(), 1, Integer::sum);
            var mod = byMod.computeIfAbsent(decision.descriptor().ownerMod(), ignored -> {
                var statuses = new EnumMap<MappingStatus, Integer>(MappingStatus.class);
                for (var status : MappingStatus.values()) {
                    statuses.put(status, 0);
                }
                return statuses;
            });
            mod.merge(decision.status(), 1, Integer::sum);
        }
        var frozenByMod = new TreeMap<String, Map<MappingStatus, Integer>>();
        byMod.forEach((mod, values) -> frozenByMod.put(mod, Collections.unmodifiableMap(values)));
        return new CompatibilityStats(Collections.unmodifiableMap(totals), Collections.unmodifiableMap(frozenByMod));
    }
}
