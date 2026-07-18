/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import io.github.polymcreborn.api.ContentDescriptor;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Resolves safe variant models and their local dependencies before any carrier is allocated. */
public final class BlockStateModelResolver {
    private static final long MAX_RESOURCE_BYTES = 8_388_608L;
    private static final int MAX_MODEL_DEPTH = 32;

    public Map<BlockState, ResolvedModel> resolve(ContentDescriptor descriptor, Block block) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(block, "block");
        Identifier blockId = Identifier.parse(descriptor.registryId());
        List<BlockState> states = block.getStateDefinition().getPossibleStates().stream()
                .sorted(java.util.Comparator.comparing(BlockStateKey::canonicalProperties)).toList();
        String blockstatePath = "assets/" + blockId.getNamespace() + "/blockstates/" + blockId.getPath() + ".json";
        ResourceBytes blockstate = findResource(blockstatePath, descriptor.ownerMod());

        if (blockstate == null) {
            if (states.size() != 1) {
                throw new ResolutionException("Missing required state variants " + blockstatePath
                        + " for " + states.size() + " safe server states");
            }
            var dependencies = new TreeMap<String, byte[]>();
            var model = Identifier.fromNamespaceAndPath(blockId.getNamespace(), "block/" + blockId.getPath());
            collectModel(model, descriptor.ownerMod(), dependencies, 0);
            return Map.of(states.getFirst(), resolved(List.of(PolymerBlockModel.of(model)), dependencies));
        }

        JsonObject root = parseObject(blockstatePath, blockstate.bytes());
        if (!root.has("variants") || !root.get("variants").isJsonObject()) {
            throw new ResolutionException("State-specific full-cube projection requires a variants object in "
                    + blockstatePath + "; multipart projection is intentionally not guessed");
        }
        JsonObject variants = root.getAsJsonObject("variants");
        var result = new LinkedHashMap<BlockState, ResolvedModel>();
        for (BlockState state : states) {
            List<Map.Entry<String, JsonElement>> matching = variants.entrySet().stream()
                    .filter(entry -> matches(state, entry.getKey(), blockstatePath))
                    .sorted(Map.Entry.comparingByKey()).toList();
            if (matching.size() != 1) {
                throw new ResolutionException("Expected exactly one safe variant for " + descriptor.registryId()
                        + "[" + BlockStateKey.canonicalProperties(state) + "] in " + blockstatePath
                        + ", found " + matching.size());
            }
            List<PolymerBlockModel> models = parseModels(matching.getFirst().getValue(), blockstatePath,
                    matching.getFirst().getKey());
            var dependencies = new TreeMap<String, byte[]>();
            dependencies.put(blockstatePath, blockstate.bytes());
            for (PolymerBlockModel model : models) {
                collectModel(model.model(), descriptor.ownerMod(), dependencies, 0);
            }
            result.put(state, resolved(models, dependencies));
        }
        return Map.copyOf(result);
    }

    private static boolean matches(BlockState state, String selector, String logicalPath) {
        if (selector.isEmpty()) {
            return true;
        }
        var seen = new java.util.HashSet<String>();
        for (String pair : selector.split(",", -1)) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals == pair.length() - 1 || pair.indexOf('=', equals + 1) >= 0) {
                throw new ResolutionException("Invalid variant selector '" + selector + "' in " + logicalPath);
            }
            String name = pair.substring(0, equals);
            if (!seen.add(name)) {
                throw new ResolutionException("Duplicate property " + name + " in " + logicalPath);
            }
            Property<?> property = state.getProperties().stream()
                    .filter(candidate -> candidate.getName().equals(name)).findFirst()
                    .orElseThrow(() -> new ResolutionException(
                            "Unknown block property " + name + " in " + logicalPath));
            String actual = propertyValue(state, property);
            List<String> expected = List.of(pair.substring(equals + 1).split("\\|", -1));
            if (expected.stream().anyMatch(String::isEmpty)
                    || expected.stream().anyMatch(value -> !isAllowedValue(property, value))) {
                throw new ResolutionException("Invalid value in variant selector '" + selector + "' in "
                        + logicalPath);
            }
            boolean accepted = expected.contains(actual);
            if (!accepted) {
                return false;
            }
        }
        return true;
    }

    private static List<PolymerBlockModel> parseModels(JsonElement element, String logicalPath, String selector) {
        var models = new ArrayList<PolymerBlockModel>();
        if (element.isJsonArray()) {
            if (element.getAsJsonArray().isEmpty()) {
                throw new ResolutionException("Empty model array for variant '" + selector + "' in " + logicalPath);
            }
            element.getAsJsonArray().forEach(value -> models.add(parseModel(value, logicalPath, selector)));
        } else {
            models.add(parseModel(element, logicalPath, selector));
        }
        return List.copyOf(models);
    }

    private static PolymerBlockModel parseModel(JsonElement element, String logicalPath, String selector) {
        if (!element.isJsonObject()) {
            throw new ResolutionException("Variant '" + selector + "' must contain a model object in "
                    + logicalPath);
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has("model") || !object.get("model").isJsonPrimitive()) {
            throw new ResolutionException("Variant '" + selector + "' has no model in " + logicalPath);
        }
        Identifier model;
        try {
            model = Identifier.parse(object.get("model").getAsString());
        } catch (RuntimeException exception) {
            throw new ResolutionException("Invalid model identifier in " + logicalPath + ": "
                    + exception.getMessage());
        }
        int x = integer(object, "x", 0, logicalPath);
        int y = integer(object, "y", 0, logicalPath);
        if (x < 0 || x > 270 || x % 90 != 0 || y < 0 || y > 270 || y % 90 != 0) {
            throw new ResolutionException("Model rotations must be 0, 90, 180, or 270 in " + logicalPath);
        }
        boolean uvLock = false;
        if (object.has("uvlock")) {
            JsonElement uvLockElement = object.get("uvlock");
            if (!uvLockElement.isJsonPrimitive() || !uvLockElement.getAsJsonPrimitive().isBoolean()) {
                throw new ResolutionException("Expected boolean uvlock in " + logicalPath);
            }
            uvLock = uvLockElement.getAsBoolean();
        }
        int weight = integer(object, "weight", 1, logicalPath);
        if (weight < 1) {
            throw new ResolutionException("Model weight must be positive in " + logicalPath);
        }
        return PolymerBlockModel.of(model, x, y, uvLock, weight);
    }

    private static int integer(JsonObject object, String name, int fallback, String logicalPath) {
        if (!object.has(name)) {
            return fallback;
        }
        try {
            JsonElement value = object.get(name);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                throw new ArithmeticException("not a JSON number");
            }
            return value.getAsBigDecimal().intValueExact();
        } catch (RuntimeException exception) {
            throw new ResolutionException("Expected integer " + name + " in " + logicalPath);
        }
    }

    private static void collectModel(Identifier model, String ownerMod, Map<String, byte[]> dependencies, int depth) {
        if (depth > MAX_MODEL_DEPTH) {
            throw new ResolutionException("Model dependency depth exceeds " + MAX_MODEL_DEPTH + " at " + model);
        }
        if (model.getPath().startsWith("builtin/")) {
            dependencies.putIfAbsent("builtin:" + model, new byte[0]);
            return;
        }
        String path = "assets/" + model.getNamespace() + "/models/" + model.getPath() + ".json";
        if (dependencies.containsKey(path)) {
            return;
        }
        ResourceBytes resource = findResource(path, ownerMod);
        if (resource == null) {
            if (model.getNamespace().equals("minecraft")) {
                dependencies.put(path, new byte[0]);
                return;
            }
            throw new ResolutionException("Missing referenced model " + path);
        }
        dependencies.put(path, resource.bytes());
        JsonObject root = parseObject(path, resource.bytes());
        if (root.has("parent")) {
            collectModel(parseIdentifier(root.get("parent"), path, "parent"), ownerMod, dependencies, depth + 1);
        }
        if (root.has("textures")) {
            if (!root.get("textures").isJsonObject()) {
                throw new ResolutionException("textures must be an object in " + path);
            }
            for (var entry : root.getAsJsonObject("textures").entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                String value = entry.getValue().getAsString();
                if (value.startsWith("#")) {
                    continue;
                }
                Identifier texture = parseIdentifier(entry.getValue(), path, "texture " + entry.getKey());
                String texturePath = "assets/" + texture.getNamespace() + "/textures/" + texture.getPath() + ".png";
                ResourceBytes textureResource = findResource(texturePath, ownerMod);
                if (textureResource == null) {
                    if (texture.getNamespace().equals("minecraft")) {
                        dependencies.putIfAbsent(texturePath, new byte[0]);
                    } else {
                        throw new ResolutionException("Missing referenced texture " + texturePath);
                    }
                } else {
                    dependencies.putIfAbsent(texturePath, textureResource.bytes());
                }
            }
        }
    }

    private static Identifier parseIdentifier(JsonElement element, String logicalPath, String field) {
        try {
            return Identifier.parse(element.getAsString());
        } catch (RuntimeException exception) {
            throw new ResolutionException("Invalid " + field + " identifier in " + logicalPath);
        }
    }

    private static ResolvedModel resolved(List<PolymerBlockModel> models, Map<String, byte[]> dependencies) {
        long totalBytes = dependencies.values().stream().mapToLong(bytes -> bytes.length).sum();
        if (totalBytes > MAX_RESOURCE_BYTES) {
            throw new ResolutionException("Resolved state model dependencies exceed safe total limit of "
                    + MAX_RESOURCE_BYTES + " bytes");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            dependencies.forEach((path, bytes) -> {
                digest.update(path.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(bytes);
                digest.update((byte) 0);
            });
            return new ResolvedModel(models, dependencies.keySet().stream().sorted().toList(),
                    HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM has no SHA-256", impossible);
        }
    }

    private static JsonObject parseObject(String logicalPath, byte[] bytes) {
        try {
            JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) {
                throw new ResolutionException("Expected a JSON object in " + logicalPath);
            }
            return parsed.getAsJsonObject();
        } catch (ResolutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ResolutionException("Invalid JSON in " + logicalPath + ": " + exception.getMessage());
        }
    }

    private static ResourceBytes findResource(String logicalPath, String ownerMod) {
        if (logicalPath.startsWith("/") || logicalPath.contains("\\") || logicalPath.contains("..")) {
            throw new ResolutionException("Unsafe resource path " + logicalPath);
        }
        var containers = new ArrayList<ModContainer>();
        FabricLoader.getInstance().getModContainer(ownerMod).ifPresent(containers::add);
        FabricLoader.getInstance().getAllMods().stream()
                .sorted(java.util.Comparator.comparing(container -> container.getMetadata().getId()))
                .filter(container -> containers.stream().noneMatch(existing -> existing == container))
                .forEach(containers::add);
        for (ModContainer container : containers) {
            for (Path root : container.getRootPaths().stream().sorted().toList()) {
                Path normalizedRoot = root.toAbsolutePath().normalize();
                Path candidate = normalizedRoot.resolve(logicalPath).normalize();
                if (!candidate.startsWith(normalizedRoot) || !Files.isRegularFile(candidate)) {
                    continue;
                }
                try {
                    long size = Files.size(candidate);
                    if (size > MAX_RESOURCE_BYTES) {
                        throw new ResolutionException("Resource exceeds safe state-model limit: " + logicalPath);
                    }
                    return new ResourceBytes(Files.readAllBytes(candidate));
                } catch (IOException exception) {
                    throw new ResolutionException("Unable to read " + logicalPath + ": " + exception.getMessage());
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<?> property) {
        Property<T> typed = (Property<T>) property;
        return typed.getName(state.getValue(typed));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> boolean isAllowedValue(Property<?> property, String value) {
        Property<T> typed = (Property<T>) property;
        return typed.getPossibleValues().stream().map(typed::getName).anyMatch(value::equals);
    }

    public record ResolvedModel(List<PolymerBlockModel> models, List<String> dependencies, String sha256) {
        public ResolvedModel {
            models = List.copyOf(models);
            dependencies = dependencies.stream().sorted().distinct().toList();
        }
    }

    public static final class ResolutionException extends RuntimeException {
        public ResolutionException(String message) {
            super(message);
        }
    }

    private record ResourceBytes(byte[] bytes) {
        private ResourceBytes {
            bytes = bytes.clone();
        }
    }
}
