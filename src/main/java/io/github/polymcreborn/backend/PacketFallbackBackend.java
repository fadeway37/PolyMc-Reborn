/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend;

/** Experimental, isolated packet fallback SPI. Polymer remains the normal backend. */
public interface PacketFallbackBackend {
    String id();

    boolean enabled();

    PacketDecision classify(String packetType);

    byte[] transform(String packetType, byte[] payload);

    enum Disposition {
        ALLOW,
        TRANSFORM,
        REJECT
    }

    record PacketDecision(Disposition disposition, String reason) {
        public PacketDecision {
            if (disposition == null || reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("Packet decisions require disposition and reason");
            }
        }
    }
}
