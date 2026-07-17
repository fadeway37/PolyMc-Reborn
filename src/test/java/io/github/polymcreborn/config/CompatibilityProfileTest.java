/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import com.google.gson.JsonParser;
import io.github.polymcreborn.api.ContentDescriptor;
import io.github.polymcreborn.api.ContentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityProfileTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesAndMatchesAConstrainedProfile() throws IOException {
        var file = write("profile.json", validProfile("demo:widget*"));
        var profile = new CompatProfileLoader().parse(file);
        var matching = ContentDescriptor.of("demo:widget_blue", "demo", ContentType.BLOCK,
                Map.of("block_property.axis", "y"));
        var other = ContentDescriptor.of("demo:gadget", "demo", ContentType.BLOCK,
                Map.of("block_property.axis", "y"));

        assertEquals("tests:profile", profile.id());
        assertTrue(profile.rules().getFirst().matches(matching));
        assertFalse(profile.rules().getFirst().matches(other));
    }

    @Test
    void rejectsUnknownNestedFieldsWithPrecisePath() throws IOException {
        var malformed = validProfile("demo:*").replace(
                "\"owner_mod\": \"demo\"", "\"owner_mod\": \"demo\", \"regex\": \".*\"");
        var file = write("unknown.json", malformed);

        var failure = assertThrows(ConfigurationException.class, () -> new CompatProfileLoader().parse(file));

        assertEquals("$.rules[0].match.regex", failure.jsonPath());
    }

    @Test
    void safeGlobSupportsOnlyBoundedLinearWildcards() {
        var glob = SafeGlob.compile("demo:tool_?*");

        assertTrue(glob.matches("demo:tool_a"));
        assertTrue(glob.matches("demo:tool_abc"));
        assertFalse(glob.matches("demo:tool_"));
        assertThrows(IllegalArgumentException.class, () -> SafeGlob.compile("demo:(.*)+"));
        assertThrows(IllegalArgumentException.class, () -> SafeGlob.compile("DEMO:*") );
        assertThrows(IllegalArgumentException.class, () -> SafeGlob.compile("a".repeat(257)));
        assertFalse(glob.matches("x".repeat(1025)));
        assertThrows(IllegalArgumentException.class,
                () -> new CompatibilityProfile.Action("diagnostic_level", "warning", false));
        assertThrows(IllegalArgumentException.class,
                () -> new CompatibilityProfile.Action("block_strategy", "complex-shape", false));
    }

    @Test
    void loaderOrdersProfilesByPriorityThenIdAndIncludesBuiltIn() throws IOException {
        var directory = Files.createDirectories(temporaryDirectory.resolve("compat.d"));
        write(directory, "z.json", validProfile("demo:z*").replace("\"tests:profile\"", "\"tests:z\"")
                .replace("\"priority\": 50", "\"priority\": 100"));
        write(directory, "a.json", validProfile("demo:a*").replace("\"tests:profile\"", "\"tests:a\"")
                .replace("\"priority\": 50", "\"priority\": 100"));

        var profiles = new CompatProfileLoader().load(directory);

        assertEquals("tests:a", profiles.get(0).id());
        assertEquals("tests:z", profiles.get(1).id());
        assertTrue(profiles.stream().anyMatch(profile -> profile.id().equals("polymc-reborn:builtin-safety")));
    }

    @Test
    void shippedSchemasAreValidJsonAndMirrorSupportedActions() throws IOException {
        var loader = getClass().getClassLoader();
        try (var compatStream = loader.getResourceAsStream(
                "polymc-reborn/schema/compat-profile-v1.schema.json");
             var configStream = loader.getResourceAsStream("polymc-reborn/schema/config-v1.schema.json")) {
            assertTrue(compatStream != null && configStream != null);
            var compatSchema = JsonParser.parseReader(new InputStreamReader(compatStream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonParser.parseReader(new InputStreamReader(configStream, StandardCharsets.UTF_8)).getAsJsonObject();
            var actionTypes = compatSchema.getAsJsonObject("properties").getAsJsonObject("rules")
                    .getAsJsonObject("items").getAsJsonObject("properties").getAsJsonObject("action")
                    .getAsJsonObject("properties").getAsJsonObject("type").getAsJsonArray("enum");
            var actual = new java.util.HashSet<String>();
            actionTypes.forEach(element -> actual.add(element.getAsString()));
            assertEquals(Set.of("disable_auto_mapping", "item_carrier_category", "block_strategy",
                    "vanilla_fallback_state", "entity_replacement", "gui_classification"), actual);
        }
        assertEquals("example:safe-materials",
                new CompatProfileLoader().parse(Path.of("examples", "compat-profile.example.json")).id());
    }

    @Test
    void rejectsPrimitiveTypeCoercionWithExactProfilePaths() throws IOException {
        var loader = new CompatProfileLoader();
        var stringPriority = write("string-priority.json",
                validProfile("demo:*").replace("\"priority\": 50", "\"priority\": \"50\""));

        var priorityFailure = assertThrows(ConfigurationException.class, () -> loader.parse(stringPriority));

        assertEquals("$.priority", priorityFailure.jsonPath());
        assertTrue(priorityFailure.getMessage().contains("Expected a JSON integer"));

        var stringBoolean = write("string-boolean.json", validProfile("demo:*").replace(
                "\"override_native_polymer\": false", "\"override_native_polymer\": \"false\""));

        var booleanFailure = assertThrows(ConfigurationException.class, () -> loader.parse(stringBoolean));

        assertEquals("$.rules[0].action.override_native_polymer", booleanFailure.jsonPath());
        assertTrue(booleanFailure.getMessage().contains("Expected a JSON boolean"));
    }

    private Path write(String name, String content) throws IOException {
        return write(temporaryDirectory, name, content);
    }

    private static Path write(Path directory, String name, String content) throws IOException {
        var file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String validProfile(String glob) {
        return """
                {
                  "schema_version": 1,
                  "id": "tests:profile",
                  "target_mod": "demo",
                  "target_version": "*",
                  "optional_dependencies": [],
                  "priority": 50,
                  "description": "Test profile",
                  "rules": [
                    {
                      "match": {
                        "glob": "%s",
                        "registry_type": "block",
                        "block_properties": {"axis": "y"},
                        "owner_mod": "demo"
                      },
                      "action": {
                        "type": "block_strategy",
                        "value": "textured-full-cube",
                        "override_native_polymer": false
                      }
                    }
                  ]
                }
                """.formatted(glob);
    }
}
