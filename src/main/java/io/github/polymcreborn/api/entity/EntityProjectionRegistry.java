/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Initialization-only registry for explicit entity projections. A frozen
 * snapshot has canonical ordering and O(1) lookups by the entity-type object.
 */
public final class EntityProjectionRegistry {
    private final Map<Identifier, EntityProjectionRegistration> byAdapterId = new TreeMap<>();
    private final Map<Identifier, EntityProjectionRegistration> byTargetId = new TreeMap<>();
    private Snapshot snapshot;

    public synchronized void register(EntityProjectionAdapter<? extends Entity> adapter) {
        if (snapshot != null) {
            throw new IllegalStateException("entity projection registry is frozen; late registration is not allowed");
        }
        Objects.requireNonNull(adapter, "adapter");
        Identifier adapterId = parseId(adapter.id(), "entity projection adapter id");
        EntityType<?> targetType = Objects.requireNonNull(adapter.targetType(), "adapter targetType");
        EntityType<?> surrogateType = Objects.requireNonNull(adapter.surrogateType(), "adapter surrogateType");
        Identifier targetId = registeredId(targetType, "target entity type");
        Identifier surrogateId = registeredId(surrogateType, "surrogate entity type");
        if (!surrogateId.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            throw new IllegalArgumentException("surrogate entity type must be vanilla for an unmodified client: "
                    + surrogateId);
        }
        double maxDistance = adapter.maxInteractionDistance();
        if (!Double.isFinite(maxDistance) || maxDistance <= 0.0D || maxDistance > 16.0D) {
            throw new IllegalArgumentException("maxInteractionDistance must be finite and in (0, 16]: "
                    + maxDistance);
        }
        var offset = Objects.requireNonNull(adapter.offset(), "adapter offset");
        if (!Double.isFinite(offset.x) || !Double.isFinite(offset.y) || !Double.isFinite(offset.z)) {
            throw new IllegalArgumentException("entity projection offset must contain only finite coordinates");
        }
        var interaction = Objects.requireNonNull(adapter.interaction(), "adapter interaction");

        if (byAdapterId.containsKey(adapterId)) {
            throw new IllegalArgumentException("duplicate entity projection adapter id: " + adapterId);
        }
        if (byTargetId.containsKey(targetId)) {
            throw new IllegalArgumentException("duplicate entity projection target type: " + targetId);
        }
        var registration = new EntityProjectionRegistration(adapterId, targetId, surrogateId,
                targetType, surrogateType, maxDistance, offset, interaction, adapter);
        byAdapterId.put(adapterId, registration);
        byTargetId.put(targetId, registration);
    }

    public synchronized Snapshot freeze() {
        if (snapshot == null) {
            var stable = new ArrayList<>(byTargetId.values());
            Collections.sort(stable);
            snapshot = new Snapshot(stable);
        }
        return snapshot;
    }

    public synchronized boolean isFrozen() {
        return snapshot != null;
    }

    public synchronized Snapshot snapshot() {
        if (snapshot == null) {
            throw new IllegalStateException("entity projection registry must be frozen before use");
        }
        return snapshot;
    }

    private static Identifier parseId(String value, String description) {
        Objects.requireNonNull(value, description);
        Identifier id = Identifier.tryParse(value);
        if (id == null || value.indexOf(':') < 1) {
            throw new IllegalArgumentException(description + " must be a valid namespaced identifier: " + value);
        }
        return id;
    }

    private static Identifier registeredId(EntityType<?> type, String description) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)
                || BuiltInRegistries.ENTITY_TYPE.getValue(id) != type) {
            throw new IllegalArgumentException(description + " is not registered");
        }
        return id;
    }

    /** Immutable lookup view. Iteration order is always target ID then adapter ID. */
    public static final class Snapshot {
        private final List<EntityProjectionRegistration> entries;
        private final Map<Identifier, EntityProjectionRegistration> byTargetId;
        private final Map<EntityType<?>, EntityProjectionRegistration> byTargetType;

        private Snapshot(List<EntityProjectionRegistration> entries) {
            this.entries = List.copyOf(entries);
            var stableById = new LinkedHashMap<Identifier, EntityProjectionRegistration>();
            var identity = new IdentityHashMap<EntityType<?>, EntityProjectionRegistration>();
            for (var entry : entries) {
                stableById.put(entry.targetTypeId(), entry);
                identity.put(entry.targetType(), entry);
            }
            this.byTargetId = Collections.unmodifiableMap(stableById);
            this.byTargetType = Collections.unmodifiableMap(identity);
        }

        public List<EntityProjectionRegistration> entries() {
            return entries;
        }

        public Optional<EntityProjectionRegistration> find(Identifier targetTypeId) {
            return Optional.ofNullable(byTargetId.get(Objects.requireNonNull(targetTypeId, "targetTypeId")));
        }

        public Optional<EntityProjectionRegistration> find(EntityType<?> targetType) {
            return Optional.ofNullable(byTargetType.get(Objects.requireNonNull(targetType, "targetType")));
        }

        public int size() {
            return entries.size();
        }
    }
}
