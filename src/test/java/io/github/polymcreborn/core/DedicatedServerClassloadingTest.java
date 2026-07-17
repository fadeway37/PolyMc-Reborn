/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DedicatedServerClassloadingTest {
    @Test
    void productionClassConstantPoolsNeverReferenceClientOnlyMinecraftPackages() throws IOException {
        var classes = Path.of(System.getProperty("user.dir"), "build", "classes", "java", "main")
                .toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(classes), "main classes must be compiled before tests");
        var checked = new AtomicInteger();

        try (var paths = Files.walk(classes)) {
            for (var path : paths.filter(file -> file.getFileName().toString().endsWith(".class")).toList()) {
                var constants = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertFalse(constants.contains("net/minecraft/client"), () -> "client reference in " + path);
                assertFalse(constants.contains("net.minecraft.client"), () -> "client reference in " + path);
                checked.incrementAndGet();
            }
        }

        assertTrue(checked.get() >= 25, "expected to inspect the complete production class set");
    }
}
