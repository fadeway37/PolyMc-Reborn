/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api.gui;

import java.util.Arrays;
import java.util.OptionalInt;

/**
 * Immutable bijection between every visible container slot and a selected slot in the real
 * server-side container. Player inventory slots are appended by the backend and are not included.
 */
public final class GuiSlotMapping {
    private final int sourceContainerSize;
    private final int[] clientToServer;
    private final int[] serverToClient;

    private GuiSlotMapping(int sourceContainerSize, int[] clientToServer) {
        if (sourceContainerSize < 1) {
            throw new IllegalArgumentException("sourceContainerSize must be positive");
        }
        if (clientToServer.length < 1) {
            throw new IllegalArgumentException("At least one projected slot is required");
        }
        this.sourceContainerSize = sourceContainerSize;
        this.clientToServer = clientToServer.clone();
        this.serverToClient = new int[sourceContainerSize];
        Arrays.fill(this.serverToClient, -1);
        for (int clientSlot = 0; clientSlot < this.clientToServer.length; clientSlot++) {
            int serverSlot = this.clientToServer[clientSlot];
            if (serverSlot < 0 || serverSlot >= sourceContainerSize) {
                throw new IllegalArgumentException("Mapped server slot " + serverSlot
                        + " for client slot " + clientSlot + " is outside [0, "
                        + sourceContainerSize + ")");
            }
            if (serverToClient[serverSlot] != -1) {
                throw new IllegalArgumentException("Server slot " + serverSlot
                        + " is mapped by more than one client slot");
            }
            serverToClient[serverSlot] = clientSlot;
        }
    }

    public static GuiSlotMapping fromClientToServer(int sourceContainerSize, int... clientToServer) {
        if (clientToServer == null) {
            throw new IllegalArgumentException("clientToServer must not be null");
        }
        return new GuiSlotMapping(sourceContainerSize, clientToServer);
    }

    public static GuiSlotMapping identity(int slotCount) {
        int[] slots = new int[slotCount];
        for (int slot = 0; slot < slotCount; slot++) {
            slots[slot] = slot;
        }
        return new GuiSlotMapping(slotCount, slots);
    }

    public int sourceContainerSize() {
        return sourceContainerSize;
    }

    public int projectedSlotCount() {
        return clientToServer.length;
    }

    public int serverSlotForClient(int clientSlot) {
        if (clientSlot < 0 || clientSlot >= clientToServer.length) {
            throw new IndexOutOfBoundsException("Projected client slot " + clientSlot
                    + " is outside [0, " + clientToServer.length + ")");
        }
        return clientToServer[clientSlot];
    }

    public OptionalInt clientSlotForServer(int serverSlot) {
        if (serverSlot < 0 || serverSlot >= sourceContainerSize) {
            throw new IndexOutOfBoundsException("Server container slot " + serverSlot
                    + " is outside [0, " + sourceContainerSize + ")");
        }
        int clientSlot = serverToClient[serverSlot];
        return clientSlot == -1 ? OptionalInt.empty() : OptionalInt.of(clientSlot);
    }

    public int[] clientToServer() {
        return clientToServer.clone();
    }
}
