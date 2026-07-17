/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

import io.github.polymcreborn.config.RebornConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicResourcePackTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sameResourcesProduceByteIdenticalZipRegardlessOfInsertionOrder() throws IOException {
        var first = pack();
        var second = pack();
        var model = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"demo:item/widget\"}}"
                .getBytes(StandardCharsets.UTF_8);
        var texture = new byte[]{1, 3, 3, 7};
        first.put("assets/demo/models/item/widget.json", model, "C:/private/mod-one.jar");
        first.put("assets/demo/textures/item/widget.png", texture, "C:/private/mod-one.jar");
        second.put("assets/demo/textures/item/widget.png", texture, "mod-one.jar");
        second.put("assets/demo/models/item/widget.json", model, "mod-one.jar");

        var firstBytes = first.buildBytes();
        var secondBytes = second.buildBytes();

        assertArrayEquals(firstBytes, secondBytes);
        assertArrayEquals(firstBytes, first.buildBytes());
        var names = zipEntryNames(firstBytes);
        var sorted = new ArrayList<>(names);
        sorted.sort(String::compareTo);
        assertEquals(sorted, names);
        assertTrue(names.contains("pack.mcmeta"));
        assertTrue(names.contains("polymc-reborn-manifest.json"));
        var metadata = new String(zipEntry(firstBytes, "pack.mcmeta"), StandardCharsets.UTF_8);
        assertTrue(metadata.contains("\"min_format\":84"));
        assertTrue(metadata.contains("\"max_format\":84"));
    }

    @Test
    void buildWritesDeterministicHashAndPathSanitizedManifest() throws IOException {
        var first = pack();
        var second = pack();
        first.put("assets/demo/lang/en_us.json", "{}".getBytes(StandardCharsets.UTF_8),
                "C:\\Users\\secret\\demo.jar");
        second.put("assets/demo/lang/en_us.json", "{}".getBytes(StandardCharsets.UTF_8), "demo.jar");

        var firstResult = first.build(temporaryDirectory.resolve("first.zip"));
        var secondResult = second.build(temporaryDirectory.resolve("second.zip"));

        assertEquals(firstResult.sha256(), secondResult.sha256());
        assertArrayEquals(Files.readAllBytes(temporaryDirectory.resolve("first.zip")),
                Files.readAllBytes(temporaryDirectory.resolve("second.zip")));
        var manifest = new String(first.snapshot().get("polymc-reborn-manifest.json"), StandardCharsets.UTF_8);
        assertTrue(manifest.contains("demo.jar"));
        assertFalse(manifest.contains("Users"));
        assertFalse(manifest.contains("secret"));
    }

    @Test
    void rejectsZipSlipAbsoluteAndPlatformSpecificPaths() {
        var pack = pack();

        for (var unsafe : List.of("../secret", "assets/../secret", "/absolute", "C:/secret",
                "assets\\demo\\file", "assets//file", "assets/./file", "assets/demo/")) {
            assertThrows(ResourcePackException.class,
                    () -> pack.put(unsafe, new byte[]{1}, "test"), unsafe);
        }
    }

    @Test
    void rejectsConflictsButReportsIdenticalDuplicates() {
        var pack = pack();
        pack.put("assets/demo/value.txt", new byte[]{1}, "first.jar");
        pack.put("assets/demo/value.txt", new byte[]{1}, "second.jar");

        var result = pack.build(temporaryDirectory.resolve("pack.zip"));

        assertEquals(1, result.duplicates().size());
        assertThrows(ResourcePackException.class,
                () -> pack.put("assets/demo/value.txt", new byte[]{2}, "third.jar"));
    }

    @Test
    void validatesMissingModelAndTextureDependencies() {
        var pack = pack();
        var model = """
                {
                  "parent": "demo:block/missing_parent",
                  "textures": {"all": "demo:block/missing_texture", "particle": "#all"}
                }
                """.getBytes(StandardCharsets.UTF_8);
        pack.put("assets/demo/models/block/widget.json", model, "demo.jar");

        var missing = pack.validateModelDependencies();

        assertEquals(2, missing.size());
        assertTrue(missing.stream().anyMatch(value -> value.contains("missing_parent")));
        assertTrue(missing.stream().anyMatch(value -> value.contains("missing_texture")));
    }

    @Test
    void enforcesEveryExtractionBound() throws IOException {
        var maxOneFile = new DeterministicResourcePack(new RebornConfig.ResourceExtractionLimits(1, 4, 4));
        maxOneFile.put("one", new byte[]{1, 2, 3, 4}, "test");
        assertThrows(ResourcePackException.class, () -> maxOneFile.put("two", new byte[]{1}, "test"));

        var maxSize = new DeterministicResourcePack(new RebornConfig.ResourceExtractionLimits(10, 2, 4));
        assertThrows(ResourcePackException.class, () -> maxSize.put("large", new byte[]{1, 2, 3}, "test"));
        var oversizedFile = temporaryDirectory.resolve("oversized-resource.bin");
        Files.write(oversizedFile, new byte[]{1, 2, 3});
        assertThrows(ResourcePackException.class,
                () -> maxSize.putFile("assets/demo/oversized.bin", oversizedFile, "test"));
        maxSize.put("one", new byte[]{1, 2}, "test");
        maxSize.put("two", new byte[]{3, 4}, "test");
        assertThrows(ResourcePackException.class, () -> maxSize.put("three", new byte[]{5}, "test"));
    }

    private static DeterministicResourcePack pack() {
        return new DeterministicResourcePack(new RebornConfig.ResourceExtractionLimits(
                100, 1_048_576, 4_194_304));
    }

    private static List<String> zipEntryNames(byte[] archive) throws IOException {
        var output = new ArrayList<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                output.add(entry.getName());
            }
        }
        return output;
    }

    private static byte[] zipEntry(byte[] archive, String requested) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (requested.equals(entry.getName())) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IOException("Missing ZIP entry " + requested);
    }
}
