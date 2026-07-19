/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import io.github.polymcreborn.api.PolyMcRebornEntrypoint;
import io.github.polymcreborn.api.entity.EntityProjectionAdapter;
import io.github.polymcreborn.api.entity.EntityProjectionInteraction;
import io.github.polymcreborn.api.entity.EntityProjectionRegistry;
import io.github.polymcreborn.api.entity.EntityProjectionComposition;
import io.github.polymcreborn.api.gui.GuiInteractionPolicy;
import io.github.polymcreborn.api.gui.GuiProjection;
import io.github.polymcreborn.api.gui.GuiProjectionRegistry;
import io.github.polymcreborn.api.gui.GuiSlotMapping;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Optional;

/** Explicit 0.2 adapters proving the modern entity and GUI extension surfaces. */
public final class ModernFixtureEntrypoint implements PolyMcRebornEntrypoint {
    @Override
    public void registerEntityProjections(EntityProjectionRegistry registry) {
        FixtureContent.bootstrap();
        registry.register(new EntityProjectionAdapter<FixtureContent.FixtureEntity>() {
            @Override
            public String id() {
                return "polymc-reborn-gametest:fixture-entity";
            }

            @Override
            public EntityType<FixtureContent.FixtureEntity> targetType() {
                return FixtureContent.ENTITY_TYPE;
            }

            @Override
            public EntityType<?> surrogateType() {
                return EntityType.ARMOR_STAND;
            }

            @Override
            public EntityProjectionComposition composition() {
                return new EntityProjectionComposition(Optional.of(EntityType.PARROT),
                        new net.minecraft.world.phys.Vec3(0.0D, 1.4D, 0.0D),
                        List.of(new EntityProjectionComposition.Equipment(
                                EquipmentSlot.HEAD, Items.GOLDEN_HELMET)));
            }

            @Override
            public EntityProjectionInteraction<FixtureContent.FixtureEntity> interaction() {
                return new EntityProjectionInteraction<>() {
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
                };
            }
        });
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
        registry.register(new io.github.polymcreborn.api.gui.GuiProjectionAdapter() {
            @Override
            public Identifier id() {
                return Identifier.fromNamespaceAndPath(FixtureContent.MOD_ID, "fixture-property-menu");
            }

            @Override
            public net.minecraft.world.inventory.MenuType<?> serverMenuType() {
                return FixtureContent.PROPERTY_MENU_TYPE;
            }

            @Override
            public GuiProjection project(net.minecraft.world.inventory.AbstractContainerMenu sourceMenu,
                                         net.minecraft.server.level.ServerPlayer player) {
                if (!(sourceMenu instanceof FixtureContent.PropertyFixtureMenu propertyMenu)) {
                    throw new IllegalArgumentException("Property adapter received another menu implementation");
                }
                return GuiProjection.furnace(propertyMenu.container(), propertyMenu.data(),
                        GuiInteractionPolicy.safeStandard());
            }
        });
    }
}
