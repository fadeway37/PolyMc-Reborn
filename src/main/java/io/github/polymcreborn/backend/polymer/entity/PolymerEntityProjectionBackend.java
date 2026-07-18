/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer.entity;

import eu.pb4.polymer.core.api.entity.PolymerEntity;
import eu.pb4.polymer.core.api.entity.PolymerEntityUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.SimpleEntityElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import io.github.polymcreborn.api.entity.EntityProjectionInteraction;
import io.github.polymcreborn.api.entity.EntityProjectionRegistration;
import io.github.polymcreborn.api.entity.EntityProjectionRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Explicit-only Polymer Virtual Entity backend. The real mod entity remains the
 * authoritative server object; an attached vanilla element is its client view.
 */
public final class PolymerEntityProjectionBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolymerEntityProjectionBackend.class);
    private static final int MAX_INTERACTION_KEYS_PER_ENTITY = 1_024;

    private final EntityProjectionRegistry.Snapshot registrations;
    private final int maximumSessions;
    private final AtomicLong nextGeneration = new AtomicLong();
    private final AtomicLong acceptedUseCount = new AtomicLong();
    private final AtomicLong acceptedAttackCount = new AtomicLong();
    private final AtomicLong authorizationRejectionCount = new AtomicLong();
    private final AtomicLong replayRejectionCount = new AtomicLong();
    private final AtomicLong adapterFailureCount = new AtomicLong();
    private final AtomicLong sessionFailureCount = new AtomicLong();
    private final Map<UUID, ProjectionSession> sessions = new ConcurrentHashMap<>();
    private volatile Map<EntityType<?>, EntityProjectionRegistration> activeAdapters = Map.of();
    private volatile Map<Identifier, String> skippedAdapters = Map.of();
    private volatile boolean installed;

    public PolymerEntityProjectionBackend(EntityProjectionRegistry.Snapshot registrations) {
        this(registrations, 65_536);
    }

    public PolymerEntityProjectionBackend(EntityProjectionRegistry.Snapshot registrations, int maximumSessions) {
        this.registrations = registrations;
        if (maximumSessions < 1 || maximumSessions > 65_536) {
            throw new IllegalArgumentException("maximumSessions must be in [1, 65536]");
        }
        this.maximumSessions = maximumSessions;
    }

    /**
     * Registers overlays and lifecycle hooks once. Vanilla targets and entity
     * types with an existing Polymer implementation are deliberately skipped.
     */
    public synchronized void install() {
        if (installed) {
            return;
        }

        var active = new IdentityHashMap<EntityType<?>, EntityProjectionRegistration>();
        var skipped = new TreeMap<Identifier, String>();
        for (var registration : registrations.entries()) {
            if (registration.targetTypeId().getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
                skipped.put(registration.targetTypeId(), "vanilla target types are never replaced");
                continue;
            }
            EntityType<?> targetType = registration.targetType();
            if (PolymerEntityUtils.isPolymerEntityType(targetType)) {
                skipped.put(registration.targetTypeId(), "existing native Polymer entity implementation wins");
                continue;
            }
            registerHiddenAnchorOverlay(registration);
            active.put(targetType, registration);
        }
        activeAdapters = Collections.unmodifiableMap(active);
        skippedAdapters = Collections.unmodifiableMap(skipped);

        ServerEntityEvents.ENTITY_LOAD.register(this::entityLoaded);
        ServerEntityEvents.ENTITY_UNLOAD.register(this::entityUnloaded);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> closeAll());
        installed = true;
    }

    public boolean isInstalled() {
        return installed;
    }

    public int activeAdapterCount() {
        return activeAdapters.size();
    }

    public int activeSessionCount() {
        return sessions.size();
    }

    public Map<Identifier, String> skippedAdapters() {
        return skippedAdapters;
    }

    public long acceptedInteractionCount() {
        return acceptedUseCount.get() + acceptedAttackCount.get();
    }

    public long authorizationRejectionCount() {
        return authorizationRejectionCount.get();
    }

    public long replayRejectionCount() {
        return replayRejectionCount.get();
    }

    public long adapterFailureCount() {
        return adapterFailureCount.get();
    }

    public long sessionFailureCount() {
        return sessionFailureCount.get();
    }

    public Optional<Long> sessionGeneration(UUID entityId) {
        var session = sessions.get(entityId);
        return session == null ? Optional.empty() : Optional.of(session.generation);
    }

    private void entityLoaded(Entity entity, ServerLevel level) {
        var registration = activeAdapters.get(entity.getType());
        if (registration == null || entity.isRemoved() || !entity.isAlive() || entity.level() != level) {
            return;
        }
        if (!sessions.containsKey(entity.getUUID()) && sessions.size() >= maximumSessions) {
            sessionFailureCount.incrementAndGet();
            LOGGER.warn("Explicit entity projection capacity {} exhausted; skipping {}",
                    maximumSessions, registration.targetTypeId());
            return;
        }

        var holder = new ElementHolder();
        var element = new AnchoredSurrogateElement(registration.surrogateType(), entity);
        element.setOffset(registration.offset());
        var session = new ProjectionSession(
                nextGeneration.incrementAndGet(), entity, level, registration, holder, element);
        element.setInteractionHandler(new GuardedInteractionHandler(session));
        holder.addElement(element);

        ProjectionSession previous = sessions.put(entity.getUUID(), session);
        if (previous != null) {
            previous.close();
        }
        try {
            EntityAttachment.ofTicking(holder, entity);
        } catch (RuntimeException exception) {
            sessions.remove(entity.getUUID(), session);
            session.close();
            sessionFailureCount.incrementAndGet();
            LOGGER.error("Could not attach explicit entity projection for {}; projection was skipped",
                    registration.targetTypeId(), exception);
        }
    }

    private void entityUnloaded(Entity entity, ServerLevel level) {
        ProjectionSession current = sessions.get(entity.getUUID());
        if (current != null && current.source == entity && current.level == level
                && sessions.remove(entity.getUUID(), current)) {
            current.close();
        }
    }

    private void closeAll() {
        for (ProjectionSession session : sessions.values()) {
            if (sessions.remove(session.source.getUUID(), session)) {
                session.close();
            }
        }
    }

    private InteractionAuthorization.Rejection authorize(ProjectionSession session, ServerPlayer player) {
        ProjectionSession current = sessions.get(session.source.getUUID());
        boolean sameSession = current == session;
        long currentGeneration = current == null ? -1L : current.generation;
        boolean sameDimension = session.source.level() == session.level && player.level() == session.level;
        boolean tracked = session.holder.getWatchingPlayers().contains(player.connection);
        double distanceSquared = sameDimension ? player.distanceToSqr(session.source) : Double.POSITIVE_INFINITY;
        double maximumDistance = session.registration.maxInteractionDistance();
        return InteractionAuthorization.evaluate(new InteractionAuthorization.Check(
                session.active,
                sameSession,
                session.generation,
                currentGeneration,
                session.source.isAlive() && !session.source.isRemoved(),
                player.isAlive() && !player.isRemoved(),
                sameDimension,
                tracked,
                distanceSquared,
                maximumDistance * maximumDistance));
    }

    private static boolean finite(Vec3 position) {
        return position != null && Double.isFinite(position.x) && Double.isFinite(position.y)
                && Double.isFinite(position.z);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerHiddenAnchorOverlay(EntityProjectionRegistration registration) {
        EntityType raw = registration.targetType();
        PolymerEntityUtils.registerOverlay(raw, entity ->
                new HiddenAnchorPolymerEntity(registration.surrogateType()));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void invokeUse(ProjectionSession session, ServerPlayer player, InteractionHand hand,
                                  Vec3 hitPosition, boolean secondaryAction) {
        var interaction = session.registration.interaction();
        ((EntityProjectionInteraction) interaction)
                .use(session.source, player, hand, hitPosition, secondaryAction);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void invokeAttack(ProjectionSession session, ServerPlayer player) {
        var interaction = session.registration.interaction();
        ((EntityProjectionInteraction) interaction)
                .attack(session.source, player);
    }

    private final class GuardedInteractionHandler implements VirtualElement.InteractionHandler {
        private final ProjectionSession session;

        private GuardedInteractionHandler(ProjectionSession session) {
            this.session = session;
        }

        @Override
        public void interact(ServerPlayer player, InteractionHand hand, Vec3 hitPosition,
                             boolean secondaryAction) {
            var rejection = authorize(session, player);
            if (!finite(hitPosition) || rejection != InteractionAuthorization.Rejection.NONE) {
                authorizationRejectionCount.incrementAndGet();
                LOGGER.debug("Rejected explicit entity use for {}: {}", session.registration.targetTypeId(),
                        finite(hitPosition) ? rejection : "NON_FINITE_HIT");
                return;
            }
            var replay = session.interactions.accept(player.getUUID(), InteractionReplayGuard.Action.USE,
                    session.level.getGameTime());
            if (replay != InteractionReplayGuard.Result.ACCEPTED) {
                replayRejectionCount.incrementAndGet();
                LOGGER.debug("Rejected duplicate explicit entity use for {}: {}",
                        session.registration.targetTypeId(), replay);
                return;
            }
            try {
                invokeUse(session, player, hand, hitPosition, secondaryAction);
                acceptedUseCount.incrementAndGet();
            } catch (RuntimeException exception) {
                adapterFailureCount.incrementAndGet();
                LOGGER.warn("Explicit entity use adapter {} failed closed", session.registration.adapterId(),
                        exception);
            }
        }

        @Override
        public void attack(ServerPlayer player) {
            var rejection = authorize(session, player);
            if (rejection != InteractionAuthorization.Rejection.NONE) {
                authorizationRejectionCount.incrementAndGet();
                LOGGER.debug("Rejected explicit entity attack for {}: {}",
                        session.registration.targetTypeId(), rejection);
                return;
            }
            var replay = session.interactions.accept(player.getUUID(), InteractionReplayGuard.Action.ATTACK,
                    session.level.getGameTime());
            if (replay != InteractionReplayGuard.Result.ACCEPTED) {
                replayRejectionCount.incrementAndGet();
                LOGGER.debug("Rejected duplicate explicit entity attack for {}: {}",
                        session.registration.targetTypeId(), replay);
                return;
            }
            try {
                invokeAttack(session, player);
                acceptedAttackCount.incrementAndGet();
            } catch (RuntimeException exception) {
                adapterFailureCount.incrementAndGet();
                LOGGER.warn("Explicit entity attack adapter {} failed closed", session.registration.adapterId(),
                        exception);
            }
        }
    }

    private static final class ProjectionSession {
        private final long generation;
        private final Entity source;
        private final ServerLevel level;
        private final EntityProjectionRegistration registration;
        private final ElementHolder holder;
        private final InteractionReplayGuard interactions =
                new InteractionReplayGuard(MAX_INTERACTION_KEYS_PER_ENTITY);
        @SuppressWarnings("unused")
        private final AnchoredSurrogateElement element;
        private volatile boolean active = true;

        private ProjectionSession(long generation, Entity source, ServerLevel level,
                                  EntityProjectionRegistration registration, ElementHolder holder,
                                  AnchoredSurrogateElement element) {
            this.generation = generation;
            this.source = source;
            this.level = level;
            this.registration = registration;
            this.holder = holder;
            this.element = element;
        }

        private void close() {
            if (!active) {
                return;
            }
            active = false;
            interactions.clear();
            holder.destroy();
        }
    }

    private static final class AnchoredSurrogateElement extends SimpleEntityElement {
        private final Entity source;

        private AnchoredSurrogateElement(EntityType<?> surrogateType, Entity source) {
            super(surrogateType);
            this.source = source;
            synchronizeVisualState();
        }

        @Override
        public void tick() {
            synchronizeVisualState();
            super.tick();
        }

        private void synchronizeVisualState() {
            synchronizeRotation(this::setRotation, source.getXRot(), source.getYRot());
            setCustomName(source.getCustomName());
            setCustomNameVisible(source.isCustomNameVisible());
            setGlowing(source.isCurrentlyGlowing());
        }
    }

    /** Polymer orders rotation as pitch then yaw, matching XRot then YRot. */
    static void synchronizeRotation(RotationSink sink, float sourcePitch, float sourceYaw) {
        sink.set(sourcePitch, sourceYaw);
    }

    @FunctionalInterface
    interface RotationSink {
        void set(float pitch, float yaw);
    }

    private record HiddenAnchorPolymerEntity(EntityType<?> surrogateType) implements PolymerEntity {
        @Override
        public EntityType<?> getPolymerEntityType(PacketContext context) {
            return surrogateType;
        }

        @Override
        public boolean sendPacketsTo(ServerPlayer player) {
            return false;
        }
    }
}
