/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import io.github.polymcreborn.api.DiagnosticCollector;
import io.github.polymcreborn.api.MappingContext;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.backend.NoOpPacketFallbackBackend;
import io.github.polymcreborn.backend.PacketFallbackBackend;
import io.github.polymcreborn.backend.polymer.MinecraftContentScanner;
import io.github.polymcreborn.backend.polymer.PolymerCompatibilityBackend;
import io.github.polymcreborn.compat.CompatibilityRegistry;
import io.github.polymcreborn.compat.DeclarativeProfileProvider;
import io.github.polymcreborn.compat.HeuristicProvider;
import io.github.polymcreborn.compat.MappingPlanner;
import io.github.polymcreborn.compat.NativePolymerProvider;
import io.github.polymcreborn.compat.SafeFallbackProvider;
import io.github.polymcreborn.compat.UnsupportedProvider;
import io.github.polymcreborn.command.RebornCommands;
import io.github.polymcreborn.config.CompatProfileLoader;
import io.github.polymcreborn.config.ConfigManager;
import io.github.polymcreborn.config.RebornConfig;
import io.github.polymcreborn.diagnostics.BoundedDiagnosticCollector;
import io.github.polymcreborn.diagnostics.CompatibilityReportWriter;
import io.github.polymcreborn.diagnostics.MappingDiffReportWriter;
import io.github.polymcreborn.legacy.LegacyCompatibilityProvider;
import io.github.polymcreborn.legacy.LegacyEntrypointBridge;
import io.github.polymcreborn.legacy.LegacyPolymerBackend;
import io.github.polymcreborn.mapping.MappingPlan;
import io.github.polymcreborn.mapping.MappingBackupService;
import io.github.polymcreborn.mapping.MappingPlanDiff;
import io.github.polymcreborn.mapping.MappingStoreDocument;
import io.github.polymcreborn.mapping.PersistentMappingStore;
import io.github.polymcreborn.pack.DeterministicResourcePack;
import io.github.polymcreborn.pack.PolymerPackService;
import io.github.polymcreborn.pack.ResourcePackReportWriter;
import io.github.polymcreborn.pack.SafeResourceCollector;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Owns the two-phase lifecycle and publishes one immutable static mapping plan. */
public final class RebornRuntime {
    private final ConfigManager configManager;
    private final RebornConfig config;
    private final BoundedDiagnosticCollector diagnostics;
    private final CompatibilityRegistry providers;
    private final LegacyEntrypointBridge legacy;
    private final ExtensionEntrypointLoader extensions;
    private final PacketFallbackBackend packetFallback = new NoOpPacketFallbackBackend();
    private final PolymerPackService polymerPackService;
    private final MappingBackupService mappingBackups;

    private volatile MappingPlan plan;
    private volatile PolymerCompatibilityBackend polymerBackend;
    private volatile boolean finalPlanAnnounced;
    private volatile Map<String, byte[]> resourceSnapshot = Map.of();

    public RebornRuntime() {
        this.configManager = new ConfigManager(FabricLoader.getInstance().getConfigDir());
        this.config = configManager.loadOrCreate();
        this.diagnostics = new BoundedDiagnosticCollector(config.cacheLimits().maxEntries());
        this.mappingBackups = new MappingBackupService(configManager.root());
        if (mappingBackups.activatePendingRollback("26.1.2", PolyMcReborn.VERSION)) {
            diagnostics.record("mapping.rollback.activated", "mappings-v1.json",
                    "A validated pending rollback was activated before static planning",
                    DiagnosticCollector.Severity.WARNING);
        }
        this.providers = new CompatibilityRegistry();
        this.legacy = new LegacyEntrypointBridge(diagnostics);
        this.extensions = new ExtensionEntrypointLoader(providers);
        this.polymerPackService = new PolymerPackService(
                configManager.cacheDirectory(), config.cacheLimits().maxBytes());

        if (config.creativeReverseMappingEnabled()) {
            throw new IllegalStateException("creative_reverse_mapping_enabled=true is not available in 0.1: "
                    + "unsigned Polymer reverse payloads are intentionally rejected");
        }
        if (config.packetFallbackEnabled()) {
            diagnostics.record("packet_fallback.unavailable", "polymc-reborn",
                    "packet_fallback_enabled was requested, but the 0.1 backend is a disabled no-op",
                    DiagnosticCollector.Severity.WARNING);
        }
        registerProviders();
        registerLifecycle();
        RebornCommands.register(this);
    }

    private void registerProviders() {
        legacy.load();
        var profiles = new CompatProfileLoader().load(configManager.compatDirectory());

        providers.register(new NativePolymerProvider());
        providers.register(new DeclarativeProfileProvider(profiles, true));
        extensions.load();
        providers.register(new LegacyCompatibilityProvider(legacy.registry()));
        providers.register(new DeclarativeProfileProvider(profiles, false));
        providers.register(new HeuristicProvider());
        providers.register(new SafeFallbackProvider());
        providers.register(new UnsupportedProvider());
    }

    private void registerLifecycle() {
        PolymerResourcePackUtils.RESOURCE_PACK_INITIALIZED_EVENT.register(() -> diagnostics.record(
                "resource-pack.initialized", "polymer-resource-pack",
                "Polymer resource-pack builder initialized; mappings finalize at the registry freeze boundary",
                DiagnosticCollector.Severity.INFO));
        PolymerResourcePackUtils.RESOURCE_PACK_CREATION_EVENT.register(builder -> {
            ensureStaticPlanFrozen();
            new TreeMap<>(resourceSnapshot).forEach(builder::addData);
        });
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ensureStaticPlanFrozen();
            finalizeBoundItemCarriers();
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            long dynamicRegistryCount = server.registryAccess().registries().count();
            diagnostics.record("lifecycle.dynamic-registry-check", "minecraft:server",
                    "Observed " + dynamicRegistryCount
                            + " server registry views without changing the frozen static plan",
                    DiagnosticCollector.Severity.INFO);
            if (Boolean.getBoolean("polymc-reborn.smoke-test")) {
                PolyMcReborn.LOGGER.info("Dedicated-server smoke test reached SERVER_STARTED; stopping cleanly");
                server.halt(false);
            }
        });
    }

    public synchronized MappingPlan ensureStaticPlanFrozen() {
        if (plan != null) {
            return plan;
        }
        var descriptors = new MinecraftContentScanner().scan();
        if (descriptors.size() > config.cacheLimits().maxEntries()) {
            throw new IllegalStateException("Discovered " + descriptors.size()
                    + " entries, exceeding cache_limits.max_entries=" + config.cacheLimits().maxEntries());
        }
        var context = new MappingContext(null, config.overrideNativePolymer(), config.safeMode(), Map.of());
        var proposed = new MappingPlanner().plan(descriptors, providers, context);
        MappingPlan finalPlan = proposed;

        if (config.enabled()) {
            new LegacyPolymerBackend(legacy.registry()).apply(proposed);
            var backend = new PolymerCompatibilityBackend(
                    new PersistentMappingStore(configManager.root()), diagnostics, config.persistentMappings(),
                    config.generateResourcePack());
            this.polymerBackend = backend;
            finalPlan = backend.apply(proposed, PolyMcReborn.VERSION);
            new MappingDiffReportWriter().write(configManager.reportsDirectory(), backend.startupDiff());
        } else {
            finalPlan = proposed.replaceDecisions(proposed.orderedDecisions().stream()
                    .map(RebornRuntime::disabledDecision).toList());
        }
        this.plan = finalPlan;
        this.resourceSnapshot = collectResources(finalPlan);
        new CompatibilityReportWriter().write(configManager.reportsDirectory(), finalPlan, diagnostics,
                config.reportFormats());

        var stats = io.github.polymcreborn.diagnostics.CompatibilityStats.from(finalPlan);
        PolyMcReborn.LOGGER.info("PolyMc Reborn pre-start plan registered: {} entries; native={}, heuristic={}, "
                        + "fallback={}, unsupported={}, errors={}",
                finalPlan.size(), stats.totals().get(MappingStatus.NATIVE),
                stats.totals().get(MappingStatus.HEURISTIC), stats.totals().get(MappingStatus.FALLBACK),
                stats.totals().get(MappingStatus.UNSUPPORTED), stats.totals().get(MappingStatus.ERROR));
        if (config.logDecisionChains()) {
            finalPlan.orderedDecisions().forEach(decision -> PolyMcReborn.LOGGER.debug(
                    "Decision {} {}: status={}, provider={}, backend={}, strategy={}, carrier={}, reasons={}",
                    decision.descriptor().contentType(), decision.descriptor().registryId(), decision.status(),
                    decision.provider(), decision.backend(), decision.strategy(), decision.clientCarrier(),
                    decision.reasonChain()));
        }
        return finalPlan;
    }

    /** Completes the second static phase after item components are bound, before a server can accept players. */
    public synchronized void finalizeBoundItemCarriers() {
        var backend = polymerBackend;
        var current = plan;
        if (backend == null || current == null) {
            return;
        }
        var finalized = backend.finalizeBoundItemCarriers(current, PolyMcReborn.VERSION);
        if (backend.hasPendingSemanticItems()) {
            return;
        }
        if (finalized != current) {
            this.plan = finalized;
            diagnostics.record("mapping.item-carriers.finalized", "minecraft:item",
                    "Semantic item carriers were locked after vanilla bound default components",
                    DiagnosticCollector.Severity.INFO);
            new CompatibilityReportWriter().write(configManager.reportsDirectory(), finalized, diagnostics,
                    config.reportFormats());
            new MappingDiffReportWriter().write(configManager.reportsDirectory(), backend.startupDiff());
        }
        if (finalPlanAnnounced) {
            return;
        }
        finalPlanAnnounced = true;
        var stats = io.github.polymcreborn.diagnostics.CompatibilityStats.from(finalized);
        PolyMcReborn.LOGGER.info("PolyMc Reborn final plan locked: {} entries; native={}, explicit={}, legacy={}, "
                        + "profile={}, heuristic={}, fallback={}, unsupported={}, errors={}",
                finalized.size(), stats.totals().get(MappingStatus.NATIVE),
                stats.totals().get(MappingStatus.EXPLICIT), stats.totals().get(MappingStatus.LEGACY),
                stats.totals().get(MappingStatus.PROFILE), stats.totals().get(MappingStatus.HEURISTIC),
                stats.totals().get(MappingStatus.FALLBACK), stats.totals().get(MappingStatus.UNSUPPORTED),
                stats.totals().get(MappingStatus.ERROR));
    }

    private static io.github.polymcreborn.api.MappingDecision disabledDecision(
            io.github.polymcreborn.api.MappingDecision decision) {
        if (decision.status() == MappingStatus.NATIVE) {
            return decision;
        }
        var reasons = new java.util.ArrayList<>(decision.reasonChain());
        reasons.add("PolyMc Reborn application is disabled by config.enabled=false");
        return new io.github.polymcreborn.api.MappingDecision(decision.descriptor(), MappingStatus.UNSUPPORTED,
                "main-config", "none", "reborn-disabled", "", 1, 100, reasons,
                decision.resourceDependencies(), decision.warnings(),
                "PolyMc Reborn is disabled in config.json");
    }

    private Map<String, byte[]> collectResources(MappingPlan mappingPlan) {
        var pack = new DeterministicResourcePack(config.resourceExtractionLimits());
        if (config.generateResourcePack()) {
            Set<String> owners = new TreeSet<>();
            mappingPlan.orderedDecisions().stream()
                    .filter(decision -> decision.status() == MappingStatus.HEURISTIC
                            || decision.status() == MappingStatus.FALLBACK
                            || decision.status() == MappingStatus.PROFILE
                            || decision.status() == MappingStatus.EXPLICIT
                            || decision.status() == MappingStatus.LEGACY)
                    .forEach(decision -> owners.add(decision.descriptor().ownerMod()));
            for (var owner : owners) {
                FabricLoader.getInstance().getModContainer(owner).ifPresentOrElse(container ->
                                container.getRootPaths().stream().sorted().forEach(root ->
                                        SafeResourceCollector.collectAssets(root, owner, pack)),
                        () -> diagnostics.record("resource.owner.missing", owner,
                                "Could not resolve a Fabric ModContainer for mapped resources",
                                DiagnosticCollector.Severity.WARNING));
            }
            for (var owned : extensions.resources()) {
                try {
                    owned.contributor().contribute(pack);
                } catch (Exception exception) {
                    throw new IllegalStateException("Resource contributor from " + owned.ownerMod() + " failed", exception);
                }
            }
            legacy.contributeResources(pack);
            var collected = pack.snapshot();
            mappingPlan.orderedDecisions().stream()
                    .filter(decision -> decision.descriptor().contentType()
                            == io.github.polymcreborn.api.ContentType.ITEM)
                    .filter(decision -> decision.status() == MappingStatus.HEURISTIC
                            || decision.status() == MappingStatus.PROFILE
                            || decision.status() == MappingStatus.EXPLICIT)
                    .forEach(decision -> {
                        var id = net.minecraft.resources.Identifier.parse(decision.descriptor().registryId());
                        String definition = "assets/" + id.getNamespace() + "/items/" + id.getPath() + ".json";
                        if (!collected.containsKey(definition)) {
                            diagnostics.record("resource.item-definition.missing", decision.descriptor().registryId(),
                                    "Missing 26.1 item definition " + definition,
                                    DiagnosticCollector.Severity.WARNING);
                        }
                    });
            for (var warning : pack.validateModelDependencies()) {
                diagnostics.record("resource.model-dependency", "resource-pack", warning,
                        DiagnosticCollector.Severity.WARNING);
            }
            for (var duplicate : pack.duplicates()) {
                diagnostics.record("resource.duplicate", "resource-pack", duplicate,
                        DiagnosticCollector.Severity.INFO);
            }
            pack.buildBytes();
        }
        return new TreeMap<>(pack.snapshot());
    }

    public RebornConfig config() {
        return config;
    }

    public ConfigManager configManager() {
        return configManager;
    }

    public BoundedDiagnosticCollector diagnostics() {
        return diagnostics;
    }

    public PacketFallbackBackend packetFallback() {
        return packetFallback;
    }

    public MappingPlan plan() {
        return ensureStaticPlanFrozen();
    }

    public PolymerPackService packService() {
        return polymerPackService;
    }

    public MappingBackupService mappingBackups() {
        return mappingBackups;
    }

    public MappingStoreDocument mappingSnapshot() {
        var backend = polymerBackend;
        return backend == null ? new PersistentMappingStore(configManager.root()).load()
                : backend.mappingSnapshot();
    }

    public MappingPlanDiff mappingDiff() {
        var backend = polymerBackend;
        return backend == null ? MappingPlanDiff.compare(MappingStoreDocument.empty(), mappingSnapshot())
                : backend.startupDiff();
    }

    public MappingBackupService.DryRunResult mappingDryRun() {
        return mappingBackups.dryRun(mappingSnapshot());
    }

    public void writeReports() {
        new CompatibilityReportWriter().write(configManager.reportsDirectory(), plan(), diagnostics,
                config.reportFormats());
    }

    public void writeReports(String requested) {
        var formats = switch (requested) {
            case "json" -> java.util.List.of("json");
            case "markdown" -> java.util.List.of("markdown");
            default -> java.util.List.of("json", "markdown");
        };
        new CompatibilityReportWriter().write(configManager.reportsDirectory(), plan(), diagnostics, formats);
    }

    public void writePackReport(io.github.polymcreborn.pack.PackBuildResult result) {
        new ResourcePackReportWriter().write(configManager.reportsDirectory(), result);
    }

    public String crashReportSummary() {
        var snapshot = plan;
        if (snapshot == null) {
            return PolyMcReborn.VERSION + "; static plan not frozen; packet fallback=" + packetFallback.enabled();
        }
        var stats = io.github.polymcreborn.diagnostics.CompatibilityStats.from(snapshot);
        return PolyMcReborn.VERSION + "; entries=" + snapshot.size()
                + "; unsupported=" + stats.totals().get(MappingStatus.UNSUPPORTED)
                + "; errors=" + stats.totals().get(MappingStatus.ERROR)
                + "; packet fallback=" + packetFallback.enabled();
    }
}
