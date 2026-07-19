/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportBundleServiceTest {
    @Test
    void redactsWindowsPathsAndCredentialAssignments() {
        int[] count = {0};
        String sanitized = SupportBundleService.redactText(
                "path=C:\\Users\\Steve\\server token=top-secret\n", count);
        assertFalse(sanitized.contains("Steve"));
        assertFalse(sanitized.contains("top-secret"));
        assertTrue(count[0] >= 2);
    }

    @Test
    void redactsUnixPathsAndJsonSecretsWithoutTouchingPublicUrls() {
        int[] count = {0};
        String sanitized = SupportBundleService.redactText(String.join("\n",
                "home=/" + "home/alice/private/server",
                "{\"token\":\"top-secret\",\"authorization\":\"Bearer-value\"}",
                "docs=https://example.invalid/public", ""), count);

        assertFalse(sanitized.contains("alice"));
        assertFalse(sanitized.contains("top-secret"));
        assertFalse(sanitized.contains("Bearer-value"));
        assertTrue(sanitized.contains("https://example.invalid/public"));
        assertTrue(count[0] >= 3);
    }
}
