/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.mapping;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Stable, explainable comparison between two mapping-store snapshots. */
public record MappingPlanDiff(List<Entry> entries, Map<ChangeKind, Integer> counts) {
    public MappingPlanDiff {
        entries = entries.stream().sorted().toList();
        var normalizedCounts = new EnumMap<ChangeKind, Integer>(ChangeKind.class);
        for (var kind : ChangeKind.values()) {
            normalizedCounts.put(kind, counts.getOrDefault(kind, 0));
        }
        counts = Collections.unmodifiableMap(normalizedCounts);
    }

    public static MappingPlanDiff compare(MappingStoreDocument before, MappingStoreDocument after) {
        return compare(before, after, Set.of(), Map.of());
    }

    /**
     * Compares snapshots and predicts capacity risk for strategies whose supplied capacity would be exceeded.
     * Invalidated keys are produced by backend validation and remain visible even when their bytes are unchanged.
     */
    public static MappingPlanDiff compare(MappingStoreDocument before, MappingStoreDocument after,
                                          Set<String> invalidatedKeys, Map<String, Integer> strategyCapacities) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(invalidatedKeys, "invalidatedKeys");
        Objects.requireNonNull(strategyCapacities, "strategyCapacities");

        var oldByKey = index(before.mappings());
        var newByKey = index(after.mappings());
        var strategyUse = new HashMap<String, Integer>();
        newByKey.values().forEach(mapping -> strategyUse.merge(mapping.strategy(), 1, Integer::sum));

        var allKeys = new java.util.TreeSet<String>();
        allKeys.addAll(oldByKey.keySet());
        allKeys.addAll(newByKey.keySet());
        var entries = new ArrayList<Entry>();
        for (var key : allKeys) {
            var previous = oldByKey.get(key);
            var proposed = newByKey.get(key);
            var changes = EnumSet.noneOf(ChangeKind.class);
            if (previous == null) {
                changes.add(ChangeKind.ADDED);
            } else if (proposed == null) {
                changes.add(ChangeKind.REMOVED);
            } else {
                if (sameAssignment(previous, proposed)) {
                    changes.add(ChangeKind.PRESERVED);
                } else {
                    changes.add(ChangeKind.REASSIGNED);
                }
                if (!previous.resourceHash().equals(proposed.resourceHash())) {
                    changes.add(ChangeKind.RESOURCE_CHANGED);
                }
            }
            if (invalidatedKeys.contains(key)) {
                changes.add(ChangeKind.INVALIDATED);
            }
            if (proposed != null) {
                Integer capacity = strategyCapacities.get(proposed.strategy());
                if (capacity != null && strategyUse.getOrDefault(proposed.strategy(), 0) > capacity) {
                    changes.add(ChangeKind.CAPACITY_RISK);
                }
            }
            entries.add(new Entry(key, previous, proposed, changes));
        }

        var counts = new EnumMap<ChangeKind, Integer>(ChangeKind.class);
        for (var kind : ChangeKind.values()) {
            counts.put(kind, 0);
        }
        entries.forEach(entry -> entry.changes().forEach(kind -> counts.merge(kind, 1, Integer::sum)));
        return new MappingPlanDiff(entries, counts);
    }

    public boolean hasIncompatibleChanges() {
        return counts.get(ChangeKind.REASSIGNED) > 0
                || counts.get(ChangeKind.INVALIDATED) > 0
                || counts.get(ChangeKind.CAPACITY_RISK) > 0;
    }

    private static Map<String, StoredMapping> index(List<StoredMapping> mappings) {
        var result = new TreeMap<String, StoredMapping>();
        mappings.forEach(mapping -> result.put(mapping.key(), mapping));
        return result;
    }

    private static boolean sameAssignment(StoredMapping left, StoredMapping right) {
        return left.strategy().equals(right.strategy())
                && left.clientCarrier().equals(right.clientCarrier());
    }

    public enum ChangeKind {
        ADDED,
        REMOVED,
        PRESERVED,
        REASSIGNED,
        INVALIDATED,
        RESOURCE_CHANGED,
        CAPACITY_RISK
    }

    public record Entry(String key, StoredMapping previous, StoredMapping proposed,
                        Set<ChangeKind> changes) implements Comparable<Entry> {
        public Entry {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Diff entry key must not be blank");
            }
            changes = Collections.unmodifiableSet(changes.isEmpty()
                    ? EnumSet.noneOf(ChangeKind.class) : EnumSet.copyOf(changes));
        }

        @Override
        public int compareTo(Entry other) {
            return key.compareTo(other.key);
        }
    }
}
