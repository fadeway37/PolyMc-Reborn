/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApiSignatureGeneratorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void changedStableSignatureFailsVerification() throws Exception {
        Path generated = temporaryDirectory.resolve("generated.txt");
        Path matching = temporaryDirectory.resolve("matching.txt");
        Path changed = temporaryDirectory.resolve("changed.txt");
        Files.writeString(generated, "TYPE STABLE interface example.Api\n"
                + "METHOD STABLE public example.Api#value():java.lang.String\n");
        Files.copy(generated, matching);
        Files.writeString(changed, "TYPE STABLE interface example.Api\n");

        assertDoesNotThrow(() -> ApiSignatureGenerator.verify(generated, matching));
        assertThrows(IllegalStateException.class,
                () -> ApiSignatureGenerator.verify(generated, changed));
    }
}
