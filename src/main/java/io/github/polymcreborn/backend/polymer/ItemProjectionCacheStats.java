/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.LongAdder;

/** Process-wide counters for the one-entry-per-overlay VANILLA semantic carrier cache. */
public final class ItemProjectionCacheStats {
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();

    private ItemProjectionCacheStats() {
    }

    static void hit() {
        HITS.increment();
    }

    static void miss() {
        MISSES.increment();
    }

    public static Map<String, Long> snapshot() {
        var snapshot = new TreeMap<String, Long>();
        snapshot.put("hits", HITS.sum());
        snapshot.put("misses", MISSES.sum());
        return Collections.unmodifiableMap(snapshot);
    }
}
