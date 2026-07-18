/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import io.github.polymcreborn.api.PolyMcRebornEntrypoint;
import io.github.polymcreborn.api.entity.EntityProjectionAdapter;
import io.github.polymcreborn.api.entity.EntityProjectionInteraction;
import io.github.polymcreborn.api.entity.EntityProjectionRegistry;
import io.github.polymcreborn.api.gui.GuiInteractionPolicy;
import io.github.polymcreborn.api.gui.GuiProjection;
import io.github.polymcreborn.api.gui.GuiProjectionRegistry;
import io.github.polymcreborn.api.gui.GuiSlotMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;

/** Explicit 0.2 adapters proving the modern entity and GUI extension surfaces. */
public final class ModernFixtureEntrypoint implements PolyMcRebornEntrypoint {
    @Override
    public void registerEntityProjections(EntityProjectionRegistry registry) {
        FixtureContent.bootstrap();
        registry.register(EntityProjectionAdapter.of(
                "polymc-reborn-gametest:fixture-entity",
                FixtureContent.ENTITY_TYPE,
                EntityType.ARMOR_STAND,
                new EntityProjectionInteraction<FixtureContent.FixtureEntity>() {
                    @Override
                    public void use(FixtureContent.FixtureEntity target,
                                    net.minecraft.server.level.ServerPlayer player,
                                    net.minecraft.world.InteractionHand hand,
                                    net.minecraft.world.phys.Vec3 hitPosition,
                                    boolean secondaryAction) {
                        target.projectedUse();
                    }

                    @Override
                    public void attack(FixtureContent.FixtureEntity target,
                                       net.minecraft.server.level.ServerPlayer player) {
                        target.hurtServer(player.level(), player.damageSources().playerAttack(player), 1.0F);
                    }
                }));
    }

    @Override
    public void registerGuiProjections(GuiProjectionRegistry registry) {
        FixtureContent.bootstrap();
        registry.register(new io.github.polymcreborn.api.gui.GuiProjectionAdapter() {
            @Override
            public Identifier id() {
                return Identifier.fromNamespaceAndPath(FixtureContent.MOD_ID, "fixture-menu");
            }

            @Override
            public net.minecraft.world.inventory.MenuType<?> serverMenuType() {
                return FixtureContent.MENU_TYPE;
            }

            @Override
            public GuiProjection project(net.minecraft.world.inventory.AbstractContainerMenu sourceMenu,
                                         net.minecraft.server.level.ServerPlayer player) {
                if (!(sourceMenu instanceof FixtureContent.FixtureMenu fixtureMenu)) {
                    throw new IllegalArgumentException("Fixture menu adapter received another menu implementation");
                }
                return new GuiProjection(fixtureMenu.container(), 3,
                        GuiSlotMapping.identity(27), GuiInteractionPolicy.safeStandard());
            }
        });
    }
}
