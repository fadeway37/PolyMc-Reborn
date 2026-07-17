/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend;

import java.util.Arrays;

/** Default disabled implementation: it never intercepts, drops, or rewrites a packet. */
public final class NoOpPacketFallbackBackend implements PacketFallbackBackend {
    @Override
    public String id() {
        return "packet-fallback-disabled";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public PacketDecision classify(String packetType) {
        return new PacketDecision(Disposition.ALLOW,
                "Experimental packet fallback is disabled; normal Polymer handling is unchanged");
    }

    @Override
    public byte[] transform(String packetType, byte[] payload) {
        return Arrays.copyOf(payload, payload.length);
    }
}
