/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.MenuType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Registration-phase-only collection of explicit GUI projection adapters. */
public final class GuiProjectionRegistry {
    private final Map<Identifier, GuiProjectionAdapter> byAdapterId = new LinkedHashMap<>();
    private final Map<MenuType<?>, GuiProjectionAdapter> byMenuType = new IdentityHashMap<>();
    private Snapshot frozenSnapshot;

    public synchronized void register(GuiProjectionAdapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        if (frozenSnapshot != null) {
            throw new IllegalStateException("GUI projection registry is frozen; late registration of "
                    + adapter.id() + " is not permitted");
        }
        Identifier id = Objects.requireNonNull(adapter.id(), "adapter.id()");
        MenuType<?> menuType = Objects.requireNonNull(adapter.serverMenuType(),
                "adapter.serverMenuType()");
        if (byAdapterId.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate GUI projection adapter id " + id);
        }
        GuiProjectionAdapter existing = byMenuType.get(menuType);
        if (existing != null) {
            throw new IllegalArgumentException("Menu type already has explicit GUI projection adapter "
                    + existing.id());
        }
        byAdapterId.put(id, adapter);
        byMenuType.put(menuType, adapter);
    }

    public synchronized Snapshot freeze() {
        if (frozenSnapshot != null) {
            return frozenSnapshot;
        }
        List<Entry> entries = new ArrayList<>(byAdapterId.size());
        for (GuiProjectionAdapter adapter : byAdapterId.values()) {
            Identifier menuTypeId = BuiltInRegistries.MENU.getKey(adapter.serverMenuType());
            if (menuTypeId == null) {
                throw new IllegalStateException("GUI adapter " + adapter.id()
                        + " targets an unregistered MenuType");
            }
            entries.add(new Entry(adapter.id(), menuTypeId, adapter));
        }
        entries.sort(Comparator.comparing(Entry::menuTypeId).thenComparing(Entry::adapterId));
        frozenSnapshot = new Snapshot(entries);
        return frozenSnapshot;
    }

    public synchronized boolean isFrozen() {
        return frozenSnapshot != null;
    }

    public record Entry(Identifier adapterId, Identifier menuTypeId, GuiProjectionAdapter adapter) {
        public Entry {
            Objects.requireNonNull(adapterId, "adapterId");
            Objects.requireNonNull(menuTypeId, "menuTypeId");
            Objects.requireNonNull(adapter, "adapter");
        }
    }

    /** Immutable O(1) lookup snapshot published after registration. */
    public static final class Snapshot {
        private final List<Entry> entries;
        private final Map<MenuType<?>, GuiProjectionAdapter> byMenuType;

        private Snapshot(List<Entry> entries) {
            this.entries = List.copyOf(entries);
            Map<MenuType<?>, GuiProjectionAdapter> lookup = new IdentityHashMap<>();
            entries.forEach(entry -> lookup.put(entry.adapter().serverMenuType(), entry.adapter()));
            this.byMenuType = Collections.unmodifiableMap(lookup);
        }

        public List<Entry> entries() {
            return entries;
        }

        public Optional<GuiProjectionAdapter> find(MenuType<?> menuType) {
            return Optional.ofNullable(byMenuType.get(Objects.requireNonNull(menuType, "menuType")));
        }
    }
}
