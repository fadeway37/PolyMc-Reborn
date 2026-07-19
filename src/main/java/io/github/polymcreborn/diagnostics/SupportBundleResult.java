/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.diagnostics;

/** Path-free result shown to operators after a support bundle build. */
public record SupportBundleResult(String fileName, String sha256, long sizeBytes,
                                  int entryCount, int redactionCount) {
}
