/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

/** Describes the authenticated client capability level. Unknown clients are vanilla. */
public enum ClientProfile {
    VANILLA,
    REBORN_COMPANION,
    TRUSTED_MODDED;

    public static ClientProfile safeDefault(ClientProfile profile) {
        return profile == null ? VANILLA : profile;
    }
}
