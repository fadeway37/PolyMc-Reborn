/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.pack;

import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPackStateServiceTest {
    @Test
    void recordsIndependentTerminalStatesAndCleansSessions() {
        var service = new PlayerPackStateService(ResourcePackPolicy.OPTIONAL, 2);
        UUID accepted = UUID.randomUUID();
        UUID declined = UUID.randomUUID();
        service.offered(accepted);
        service.offered(declined);
        service.response(accepted, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        service.response(accepted, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        service.response(declined, ServerboundResourcePackPacket.Action.DECLINED);
        service.response(declined, ServerboundResourcePackPacket.Action.DECLINED);

        assertEquals(PlayerPackStateService.State.APPLIED, service.state(accepted));
        assertEquals(PlayerPackStateService.State.DECLINED, service.state(declined));
        assertEquals(1, service.stats().applied());
        assertEquals(1, service.stats().declined());

        service.disconnected(accepted);
        assertEquals(PlayerPackStateService.State.UNKNOWN, service.state(accepted));
        assertEquals(PlayerPackStateService.State.DECLINED, service.state(declined));
    }

    @Test
    void disabledPolicyNeverRetainsAnEnabledState() {
        var service = new PlayerPackStateService(ResourcePackPolicy.DISABLED, 1);
        UUID player = UUID.randomUUID();
        service.offered(player);
        service.response(player, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        assertEquals(PlayerPackStateService.State.DISABLED, service.state(player));
    }

    @Test
    void capacityIsBoundedAndReported() {
        var service = new PlayerPackStateService(ResourcePackPolicy.REQUIRED, 1);
        service.offered(UUID.randomUUID());
        service.offered(UUID.randomUUID());
        assertEquals(1, service.snapshot().size());
        assertEquals(1, service.rejectedCapacityCount());
    }

    @Test
    void contextFreePlanningQueriesUseFrozenResourcesExceptWhenDisabled() {
        assertTrue(new PlayerPackStateService(ResourcePackPolicy.REQUIRED, 1)
                .customResourcesAllowed(null));
        assertTrue(new PlayerPackStateService(ResourcePackPolicy.OPTIONAL, 1)
                .customResourcesAllowed(null));
        assertFalse(new PlayerPackStateService(ResourcePackPolicy.DISABLED, 1)
                .customResourcesAllowed(null));
    }
}
