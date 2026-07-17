/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable inputs that can affect planning; it never contains live player or packet state. */
public record MappingContext(
        ClientProfile clientProfile,
        boolean overrideNativePolymer,
        boolean safeMode,
        Map<String, String> options) {

    public MappingContext {
        clientProfile = ClientProfile.safeDefault(clientProfile);
        options = Map.copyOf(new TreeMap<>(Objects.requireNonNull(options, "options")));
    }

    public static MappingContext vanillaSafe() {
        return new MappingContext(ClientProfile.VANILLA, false, true, Map.of());
    }
}
