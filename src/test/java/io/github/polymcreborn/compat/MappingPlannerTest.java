/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.compat;

import io.github.polymcreborn.api.CompatibilityProvider;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingDecision;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.api.ProviderTier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingPlannerTest {
    @Test
    void nativePolymerWinsByDefaultAndAllCandidatesRemainExplainable() {
        var descriptor = descriptor("demo:native", ContentType.ITEM,
                Map.of("native_polymer", "true", "item_category", "food"));
        var registry = standardRegistry();

        var plan = new MappingPlanner().plan(List.of(descriptor), registry, MappingContext.vanillaSafe());

        assertEquals(MappingStatus.NATIVE, plan.decision(descriptor).status());
        assertEquals("native-polymer", plan.decision(descriptor).provider());
        assertEquals(List.of("native-polymer", "admin", "builtin-heuristics", "unsupported"),
                plan.candidateTrace(descriptor).stream().map(CandidateTrace::provider).toList());
        assertTrue(plan.candidateTrace(descriptor).stream()
                .filter(trace -> trace.provider().equals("admin"))
                .findFirst().orElseThrow().matched());
    }

    @Test
    void administratorCanOverrideNativeOnlyWhenDangerousSwitchIsEnabled() {
        var descriptor = descriptor("demo:native", ContentType.ITEM, Map.of("native_polymer", "true"));
        var registry = standardRegistry();
        var context = new MappingContext(null, true, true, Map.of());

        var plan = new MappingPlanner().plan(List.of(descriptor), registry, context);

        assertEquals("admin", plan.decision(descriptor).provider());
        assertEquals(MappingStatus.PROFILE, plan.decision(descriptor).status());
    }

    @Test
    void planningOrderIsStableForEveryInputPermutation() {
        var descriptors = List.of(
                descriptor("zeta:thing", ContentType.ITEM, Map.of("item_category", "material")),
                descriptor("alpha:thing", ContentType.BLOCK,
                        Map.of("full_cube", "true", "stable_shape", "true", "has_block_entity", "false")),
                descriptor("alpha:other", ContentType.ENTITY, Map.of()));
        var reversed = new ArrayList<>(descriptors);
        Collections.reverse(reversed);

        var first = new MappingPlanner().plan(descriptors, standardRegistryWithoutAdmin(), MappingContext.vanillaSafe());
        var second = new MappingPlanner().plan(reversed, standardRegistryWithoutAdmin(), MappingContext.vanillaSafe());

        assertEquals(List.of("alpha:other", "alpha:thing", "zeta:thing"), first.orderedDecisions().stream()
                .map(decision -> decision.descriptor().registryId()).toList());
        assertEquals(first.orderedDecisions(), second.orderedDecisions());
        assertEquals(first.orderedDecisions().stream().map(MappingDecision::provider).toList(),
                second.orderedDecisions().stream().map(MappingDecision::provider).toList());
    }

    @Test
    void freezesRegistryAndRejectsLateOrDuplicateProviders() {
        var registry = new CompatibilityRegistry();
        registry.register(new NativePolymerProvider());
        assertThrows(IllegalArgumentException.class, () -> registry.register(new NativePolymerProvider()));

        registry.freezeAndOrder(MappingContext.vanillaSafe());

        assertTrue(registry.isFrozen());
        assertThrows(IllegalStateException.class, () -> registry.register(new HeuristicProvider()));
    }

    @Test
    void providerFailureBecomesExplicitErrorInsteadOfStoppingDiscovery() {
        var descriptor = descriptor("demo:broken", ContentType.ITEM, Map.of());
        var registry = new CompatibilityRegistry();
        registry.register(new CompatibilityProvider() {
            @Override
            public String id() {
                return "broken-provider";
            }

            @Override
            public ProviderTier tier() {
                return ProviderTier.EXPLICIT;
            }

            @Override
            public Optional<MappingDecision> evaluate(ContentDescriptor ignored, MappingContext context) {
                throw new IllegalStateException("deliberate failure");
            }
        });
        registry.register(new UnsupportedProvider());

        var plan = new MappingPlanner().plan(List.of(descriptor), registry, MappingContext.vanillaSafe());

        assertEquals(MappingStatus.ERROR, plan.decision(descriptor).status());
        assertTrue(plan.decision(descriptor).failureReason().contains("deliberate failure"));
        assertFalse(plan.candidateTrace(descriptor).isEmpty());
    }

    private static CompatibilityRegistry standardRegistry() {
        var registry = new CompatibilityRegistry();
        registry.register(new NativePolymerProvider());
        registry.register(new FixedProvider("admin", ProviderTier.ADMIN_FORCE, MappingStatus.PROFILE));
        registry.register(new HeuristicProvider());
        registry.register(new UnsupportedProvider());
        return registry;
    }

    private static CompatibilityRegistry standardRegistryWithoutAdmin() {
        var registry = new CompatibilityRegistry();
        registry.register(new NativePolymerProvider());
        registry.register(new HeuristicProvider());
        registry.register(new SafeFallbackProvider());
        registry.register(new UnsupportedProvider());
        return registry;
    }

    private static ContentDescriptor descriptor(String id, ContentType type, Map<String, String> attributes) {
        return ContentDescriptor.of(id, id.substring(0, id.indexOf(':')), type, attributes);
    }

    private record FixedProvider(String id, ProviderTier tier, MappingStatus status)
            implements CompatibilityProvider {
        @Override
        public Optional<MappingDecision> evaluate(ContentDescriptor descriptor, MappingContext context) {
            return Optional.of(new MappingDecision(descriptor, status, id, "polymer", "fixed",
                    "minecraft:paper", 1, 0, List.of("test proposal"), List.of(), List.of(), null));
        }
    }
}
