/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.legacy;

import io.github.polymcreborn.api.DiagnosticCollector;
import io.github.polymcreborn.diagnostics.BoundedDiagnosticCollector;
import io.github.polymcreborn.pack.DeterministicResourcePack;
import io.github.theepicblock.polymc.api.PolyMcEntrypoint;
import io.github.theepicblock.polymc.api.PolyRegistry;
import io.github.theepicblock.polymc.api.resource.ModdedResources;
import io.github.theepicblock.polymc.api.resource.PolyMcResourcePack;
import io.github.theepicblock.polymc.impl.misc.logging.SimpleLogger;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Lazily filters known broken foreign metadata before instantiating old {@code polymc} entrypoints. */
public final class LegacyEntrypointBridge {
    private final PolyRegistry registry = new PolyRegistry();
    private final BoundedDiagnosticCollector diagnostics;
    private final List<LoadedEntrypoint> entrypoints = new ArrayList<>();

    public LegacyEntrypointBridge(BoundedDiagnosticCollector diagnostics) {
        this.diagnostics = diagnostics;
    }

    public void load() {
        var containers = FabricLoader.getInstance()
                .getEntrypointContainers("polymc", PolyMcEntrypoint.class).stream()
                .filter(container -> !isKnownNonBridgeEntrypoint(container))
                .sorted(Comparator.comparing((EntrypointContainer<PolyMcEntrypoint> container) ->
                                container.getProvider().getMetadata().getId())
                        .thenComparing(container -> String.valueOf(container.getDefinition())))
                .toList();
        for (var container : containers) {
            String provider = container.getProvider().getMetadata().getId();
            try {
                var entrypoint = container.getEntrypoint();
                entrypoint.registerPolys(registry);
                entrypoints.add(new LoadedEntrypoint(provider, entrypoint));
                diagnostics.record("legacy.entrypoint.loaded", provider,
                        "Loaded source-migration polymc entrypoint", DiagnosticCollector.Severity.INFO);
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Legacy polymc entrypoint from " + provider + " failed", exception);
            }
        }
    }

    public PolyRegistry registry() {
        return registry;
    }

    public void contributeResources(DeterministicResourcePack pack) {
        var resources = new ModdedResources() {
            @Override
            public byte[] getResource(String relativePath) {
                var data = pack.snapshot().get(DeterministicResourcePack.normalize(relativePath));
                return data == null ? null : data.clone();
            }

            @Override
            public Set<String> paths() {
                return pack.snapshot().keySet();
            }
        };
        for (var loaded : entrypoints) {
            PolyMcResourcePack legacyPack = (path, data) -> pack.put(path, data, loaded.provider());
            try {
                loaded.entrypoint().registerModSpecificResources(resources, legacyPack, logger(loaded.provider()));
            } catch (RuntimeException exception) {
                throw new IllegalStateException("Legacy resources from " + loaded.provider() + " failed", exception);
            }
        }
    }

    private SimpleLogger logger(String provider) {
        return new SimpleLogger() {
            @Override
            public void info(String message) {
                diagnostics.record("legacy.resource.info", provider, message, DiagnosticCollector.Severity.INFO);
            }

            @Override
            public void warn(String message) {
                diagnostics.record("legacy.resource.warning", provider, message, DiagnosticCollector.Severity.WARNING);
            }

            @Override
            public void error(String message, Throwable throwable) {
                diagnostics.record("legacy.resource.error", provider,
                        message + ": " + throwable.getClass().getSimpleName(), DiagnosticCollector.Severity.ERROR);
            }
        };
    }

    private static boolean isKnownNonBridgeEntrypoint(EntrypointContainer<PolyMcEntrypoint> container) {
        String provider = container.getProvider().getMetadata().getId();
        return provider.equals("polymer-blocks") || provider.equals("polymc-reborn");
    }

    private record LoadedEntrypoint(String provider, PolyMcEntrypoint entrypoint) {
    }
}
