/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import io.github.polymcreborn.api.DiagnosticCollector;
import net.minecraft.core.RegistrySynchronization;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fail-closed filtering for dynamic entries whose encoded form depends on a
 * static registry entry that is intentionally server-only for vanilla clients.
 */
public final class DynamicRegistrySanitizer {
    private static volatile Set<String> hiddenStaticIdentifiers = Set.of();
    private static volatile DiagnosticCollector diagnostics;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private DynamicRegistrySanitizer() {
    }

    public static void configure(Set<String> identifiers, DiagnosticCollector collector) {
        hiddenStaticIdentifiers = Set.copyOf(new TreeSet<>(identifiers));
        diagnostics = Objects.requireNonNull(collector, "collector");
        REPORTED.clear();
    }

    public static List<RegistrySynchronization.PackedRegistryEntry> filter(
            ResourceKey<?> registry,
            List<RegistrySynchronization.PackedRegistryEntry> entries) {
        var hidden = hiddenStaticIdentifiers;
        if (hidden.isEmpty()) {
            return entries;
        }
        return filterEntries(registry, entries, hidden, diagnostics);
    }

    static Set<String> hiddenStaticIdentifiers() {
        return hiddenStaticIdentifiers;
    }

    static void reportOnce(String code, String reportKey, String registryId, String message) {
        var collector = diagnostics;
        if (collector != null && REPORTED.add(code + "|" + reportKey)) {
            collector.record(code, registryId, message, DiagnosticCollector.Severity.WARNING);
        }
    }

    static List<RegistrySynchronization.PackedRegistryEntry> filterEntries(
            ResourceKey<?> registry,
            List<RegistrySynchronization.PackedRegistryEntry> entries,
            Set<String> hidden,
            DiagnosticCollector collector) {
        var safe = new ArrayList<RegistrySynchronization.PackedRegistryEntry>(entries.size());
        boolean changed = false;
        for (var entry : entries) {
            var blockedReference = entry.data().flatMap(data -> firstHiddenReference(data, hidden));
            if (blockedReference.isEmpty()) {
                safe.add(entry);
                continue;
            }
            changed = true;
            String registryId = registry.identifier().toString();
            String reportKey = registryId + "|" + entry.id();
            if (collector != null && REPORTED.add("registry.dynamic.filtered|" + reportKey)) {
                collector.record("registry.dynamic.filtered", entry.id().toString(),
                        "Excluded dynamic entry from " + registryId
                                + " because its network form references server-only static id "
                                + blockedReference.orElseThrow(),
                        DiagnosticCollector.Severity.WARNING);
            }
        }
        return changed ? List.copyOf(safe) : entries;
    }

    private static java.util.Optional<String> firstHiddenReference(Tag tag, Set<String> hidden) {
        if (tag instanceof StringTag string) {
            return hidden.contains(string.value())
                    ? java.util.Optional.of(string.value()) : java.util.Optional.empty();
        }
        if (tag instanceof CompoundTag compound) {
            for (var entry : compound.entrySet().stream()
                    .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                if (hidden.contains(entry.getKey())) {
                    return java.util.Optional.of(entry.getKey());
                }
                var nested = firstHiddenReference(entry.getValue(), hidden);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        } else if (tag instanceof ListTag list) {
            for (var element : list) {
                var nested = firstHiddenReference(element, hidden);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return java.util.Optional.empty();
    }
}
