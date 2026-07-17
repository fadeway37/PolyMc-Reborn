/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import com.google.gson.JsonObject;

/** Future schema migrations must be explicit, deterministic, and non-destructive. */
public interface CompatProfileMigrator {
    int fromVersion();

    int toVersion();

    JsonObject migrate(JsonObject input);
}
