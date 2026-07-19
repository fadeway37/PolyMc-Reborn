/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsStrictDefaultConfigurationAndDirectoryLayout() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);

        var config = manager.loadOrCreate();

        assertEquals(RebornConfig.defaults(), config);
        assertEquals(config, manager.validate());
        assertTrue(Files.isDirectory(manager.compatDirectory()));
        assertTrue(Files.isDirectory(manager.reportsDirectory()));
        assertTrue(Files.isDirectory(manager.cacheDirectory()));
        assertTrue(Files.isDirectory(manager.supportDirectory()));
        assertTrue(Files.readString(manager.root().resolve("config.json"), StandardCharsets.UTF_8)
                .endsWith(System.lineSeparator()));
    }

    @Test
    void rejectsUnknownFieldsWithTheirJsonPath() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        var file = manager.root().resolve("config.json");
        var text = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, text.replaceFirst("\\{", "{\n  \"surprise\": true,"),
                StandardCharsets.UTF_8);

        var failure = assertThrows(ConfigurationException.class, manager::validate);

        assertEquals("$.surprise", failure.jsonPath());
        assertTrue(failure.getMessage().contains("Unknown field"));
    }

    @Test
    void rejectsMissingRequiredFields() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        var file = manager.root().resolve("config.json");
        var text = Files.readString(file, StandardCharsets.UTF_8);
        Files.writeString(file, text.replace("  \"enabled\": true,\n", ""), StandardCharsets.UTF_8);

        var failure = assertThrows(ConfigurationException.class, manager::validate);

        assertEquals("$.enabled", failure.jsonPath());
        assertTrue(failure.getMessage().contains("Required field"));
    }

    @Test
    void rejectsUnsafeLimitValuesWithoutReplacingTheFile() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        var file = manager.root().resolve("config.json");
        var invalid = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"max_entries\": 4096", "\"max_entries\": 0");
        Files.writeString(file, invalid, StandardCharsets.UTF_8);

        assertThrows(ConfigurationException.class, manager::validate);
        assertEquals(invalid, Files.readString(file, StandardCharsets.UTF_8));
        try (var files = Files.list(manager.root())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsMalformedJson() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        Files.writeString(manager.root().resolve("config.json"), "{broken", StandardCharsets.UTF_8);

        var failure = assertThrows(ConfigurationException.class, manager::validate);

        assertTrue(failure.getMessage().contains("Unable to parse JSON"));
    }

    @Test
    void rejectsStringInsteadOfBooleanWithoutGsonCoercion() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        var file = manager.root().resolve("config.json");
        var invalid = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"enabled\": true", "\"enabled\": \"true\"");
        Files.writeString(file, invalid, StandardCharsets.UTF_8);

        var failure = assertThrows(ConfigurationException.class, manager::validate);

        assertEquals("$.enabled", failure.jsonPath());
        assertTrue(failure.getMessage().contains("Expected a JSON boolean"));
    }

    @Test
    void rejectsStringInsteadOfIntegerWithoutGsonCoercion() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        var file = manager.root().resolve("config.json");
        var invalid = Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"max_entries\": 4096", "\"max_entries\": \"4096\"");
        Files.writeString(file, invalid, StandardCharsets.UTF_8);

        var failure = assertThrows(ConfigurationException.class, manager::validate);

        assertEquals("$.cache_limits.max_entries", failure.jsonPath());
        assertTrue(failure.getMessage().contains("Expected a JSON integer"));
    }

    @Test
    void acceptsA02ConfigWithoutPackPolicyAsRequired() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        var file = manager.root().resolve("config.json");
        String old = Files.readString(file, StandardCharsets.UTF_8)
                .replace("  \"resource_pack_policy\": \"REQUIRED\",\n", "");
        Files.writeString(file, old, StandardCharsets.UTF_8);

        assertEquals(io.github.polymcreborn.pack.ResourcePackPolicy.REQUIRED,
                manager.validate().resourcePackPolicy());
    }

    @Test
    void rejectsUnknownPackPolicyWithExactPath() throws IOException {
        var manager = new ConfigManager(temporaryDirectory);
        manager.loadOrCreate();
        var file = manager.root().resolve("config.json");
        Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8)
                .replace("\"resource_pack_policy\": \"REQUIRED\"",
                        "\"resource_pack_policy\": \"SURPRISE\""), StandardCharsets.UTF_8);

        var failure = assertThrows(ConfigurationException.class, manager::validate);
        assertEquals("$.resource_pack_policy", failure.jsonPath());
    }
}
