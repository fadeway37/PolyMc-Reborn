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
        UUID acceptedPack = UUID.randomUUID();
        UUID declinedPack = UUID.randomUUID();
        service.offered(accepted, acceptedPack);
        service.offered(declined, declinedPack);
        service.response(accepted, acceptedPack,
                ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        service.response(accepted, acceptedPack,
                ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        service.response(declined, declinedPack, ServerboundResourcePackPacket.Action.DECLINED);
        service.response(declined, declinedPack, ServerboundResourcePackPacket.Action.DECLINED);

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
        UUID pack = UUID.randomUUID();
        service.offered(player, pack);
        service.response(player, pack, ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED);
        assertEquals(PlayerPackStateService.State.DISABLED, service.state(player));
    }

    @Test
    void requiredDeclineHasAnExplicitFailClosedState() {
        var service = new PlayerPackStateService(ResourcePackPolicy.REQUIRED, 1);
        UUID player = UUID.randomUUID();
        UUID pack = UUID.randomUUID();
        service.offered(player, pack);

        assertEquals(PlayerPackStateService.State.REQUIRED_REJECTED,
                service.response(player, pack, ServerboundResourcePackPacket.Action.DECLINED));
        assertEquals(PlayerPackStateService.State.REQUIRED_REJECTED, service.state(player));
        assertEquals(1, service.stats().declined());
        assertEquals(1, service.stats().requiredRejected());
    }

    @Test
    void requiredOfferClosedBeforeAResponseIsRecordedAsRejected() {
        var service = new PlayerPackStateService(ResourcePackPolicy.REQUIRED, 1);
        UUID player = UUID.randomUUID();
        service.offered(player, UUID.randomUUID());

        assertEquals(PlayerPackStateService.State.REQUIRED_REJECTED,
                service.disconnected(player));
        assertEquals(PlayerPackStateService.State.UNKNOWN, service.state(player));
        assertEquals(1, service.stats().requiredRejected());
    }

    @Test
    void capacityIsBoundedAndReported() {
        var service = new PlayerPackStateService(ResourcePackPolicy.REQUIRED, 1);
        service.offered(UUID.randomUUID(), UUID.randomUUID());
        service.offered(UUID.randomUUID(), UUID.randomUUID());
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

    @Test
    void ignoresStalePackIdsAndKeepsTerminalResponsesIdempotent() {
        var service = new PlayerPackStateService(ResourcePackPolicy.OPTIONAL, 1);
        UUID player = UUID.randomUUID();
        UUID currentPack = UUID.randomUUID();
        service.offered(player, currentPack);

        assertEquals(PlayerPackStateService.State.OFFERED,
                service.response(player, UUID.randomUUID(),
                        ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
        assertEquals(0, service.stats().applied());
        assertEquals(PlayerPackStateService.State.APPLIED,
                service.response(player, currentPack,
                        ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
        assertEquals(PlayerPackStateService.State.APPLIED,
                service.response(player, currentPack, ServerboundResourcePackPacket.Action.DECLINED));
        assertEquals(1, service.stats().applied());
        assertEquals(0, service.stats().declined());
        assertEquals(0, service.stats().failed());
    }
}
