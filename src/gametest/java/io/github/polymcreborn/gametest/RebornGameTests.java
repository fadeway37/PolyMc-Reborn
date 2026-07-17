/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import eu.pb4.polymer.core.api.block.PolymerBlock;
import eu.pb4.polymer.core.api.item.PolymerItem;
import eu.pb4.polymer.core.api.utils.PolymerSyncedObject;
import io.github.polymcreborn.api.ContentType;
import io.github.polymcreborn.api.MappingStatus;
import io.github.polymcreborn.core.PolyMcReborn;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

/** Server-side integration tests over real Fabric registries and Polymer overlays. */
public final class RebornGameTests {
    @GameTest
    public void automaticItemAndBlockMappings(GameTestHelper helper) {
        var plan = PolyMcReborn.runtime().plan();
        helper.assertTrue(status(plan, ContentType.ITEM, "basic_item") == MappingStatus.HEURISTIC,
                "basic custom item should use the heuristic Polymer path");
        helper.assertTrue(status(plan, ContentType.BLOCK, "full_block") == MappingStatus.HEURISTIC,
                "simple full cube should use the heuristic Polymer path");
        helper.assertTrue(status(plan, ContentType.BLOCK, "stateful_block") == MappingStatus.HEURISTIC,
                "multi-state full cube should retain a safe mapping classification");
        helper.assertTrue("false,true".equals(plan.decision(ContentType.BLOCK,
                        FixtureContent.MOD_ID + ":stateful_block").descriptor().attributes()
                        .get("block_property.active")),
                "stateful discovery should expose deterministic allowed property values");
        helper.assertTrue(PolymerSyncedObject.getSyncedObject(
                        BuiltInRegistries.ITEM, FixtureContent.BASIC_ITEM) instanceof PolymerItem,
                "automatic item overlay should be registered");
        helper.assertTrue(PolymerSyncedObject.getSyncedObject(
                        BuiltInRegistries.BLOCK, FixtureContent.FULL_BLOCK) instanceof PolymerBlock,
                "automatic block overlay should be registered");
        helper.assertTrue(status(plan, ContentType.ITEM, "full_block") == MappingStatus.HEURISTIC,
                "the corresponding block item should have a mapping decision");
        helper.assertTrue(PolymerSyncedObject.getSyncedObject(
                        BuiltInRegistries.ITEM, FixtureContent.FULL_BLOCK.asItem()) instanceof PolymerItem,
                "the corresponding block item overlay should be registered");
        helper.succeed();
    }

    @GameTest
    public void nativeMappingsAndUnsafeShapesArePreserved(GameTestHelper helper) {
        var plan = PolyMcReborn.runtime().plan();
        helper.assertTrue(status(plan, ContentType.ITEM, "native_item") == MappingStatus.NATIVE,
                "native Polymer item overlay must win");
        helper.assertTrue(status(plan, ContentType.BLOCK, "native_block") == MappingStatus.NATIVE,
                "native Polymer block overlay must win");
        helper.assertTrue(status(plan, ContentType.BLOCK, "non_full_block") == MappingStatus.UNSUPPORTED,
                "non-full shape must not be guessed");
        helper.assertTrue(status(plan, ContentType.BLOCK, "block_entity_block") == MappingStatus.UNSUPPORTED,
                "block entity must not be projected as a simple cube");
        var throwingShape = plan.decision(ContentType.BLOCK,
                FixtureContent.MOD_ID + ":throwing_shape_block");
        helper.assertTrue(throwingShape != null && throwingShape.status() == MappingStatus.UNSUPPORTED,
                "a block that throws during shape analysis must fail closed without aborting discovery");
        helper.assertTrue(throwingShape.descriptor().booleanAttribute("shape_analysis_failed")
                        && "java.lang.IllegalStateException".equals(
                        throwingShape.descriptor().attributes().get("shape_analysis_error")),
                "the isolated shape-analysis failure should remain explainable in the frozen descriptor");
        helper.assertTrue(throwingShape.failureReason().contains("shape analysis failed safely"),
                "the unsupported decision should record the isolated shape-analysis failure");
        helper.succeed();
    }

    @GameTest
    public void legacyEntrypointAndFutureSpisAreClassified(GameTestHelper helper) {
        var plan = PolyMcReborn.runtime().plan();
        helper.assertTrue(status(plan, ContentType.ITEM, "legacy_item") == MappingStatus.LEGACY,
                "legacy polymc item adapter should be collected");
        helper.assertTrue(status(plan, ContentType.BLOCK, "legacy_block") == MappingStatus.LEGACY,
                "legacy polymc block adapter should be collected");
        helper.assertTrue(status(plan, ContentType.ENTITY, "fixture_entity") == MappingStatus.UNSUPPORTED,
                "generic entity projection is intentionally unsupported in 0.1");
        helper.assertTrue(status(plan, ContentType.GUI, "fixture_menu") == MappingStatus.UNSUPPORTED,
                "generic GUI projection is intentionally unsupported in 0.1");
        helper.assertFalse(PolyMcReborn.runtime().packetFallback().enabled(),
                "packet fallback must remain disabled by default");
        helper.succeed();
    }

    @GameTest
    public void semanticItemProjectionFiltersUnsafeComponents(GameTestHelper helper) {
        var foodOverlay = (PolymerItem) PolymerSyncedObject.getSyncedObject(
                BuiltInRegistries.ITEM, FixtureContent.FOOD_ITEM);
        var input = new ItemStack(FixtureContent.FOOD_ITEM, 3);
        input.set(DataComponents.CUSTOM_NAME, Component.literal("Fixture food"));
        var unsafe = new CompoundTag();
        unsafe.putString("server_only", "must-not-cross");
        input.set(DataComponents.CUSTOM_DATA, CustomData.of(unsafe));
        var projected = foodOverlay.getPolymerItemStack(input, TooltipFlag.NORMAL, null,
                helper.getLevel().registryAccess());
        helper.assertTrue(projected.getItem() == Items.APPLE,
                "bound consumable components should select the semantic apple carrier");
        helper.assertTrue(Component.literal("Fixture food").equals(projected.get(DataComponents.CUSTOM_NAME)),
                "safe display name should survive projection");
        helper.assertTrue(projected.get(DataComponents.CUSTOM_DATA) == null,
                "server-only custom data must be filtered");
        helper.assertTrue(BuiltInRegistries.ITEM.getKey(FixtureContent.FOOD_ITEM)
                        .equals(projected.get(DataComponents.ITEM_MODEL)),
                "the projected stack should reference the collected 26.1 item definition");

        var toolOverlay = (PolymerItem) PolymerSyncedObject.getSyncedObject(
                BuiltInRegistries.ITEM, FixtureContent.TOOL_ITEM);
        var tool = toolOverlay.getPolymerItemStack(new ItemStack(FixtureContent.TOOL_ITEM), TooltipFlag.NORMAL,
                null, helper.getLevel().registryAccess());
        helper.assertTrue(tool.getItem() == Items.IRON_PICKAXE,
                "tool components should select the semantic pickaxe carrier");
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void deterministicPolymerPackBuild(GameTestHelper helper) {
        var runtime = PolyMcReborn.runtime();
        var first = runtime.packService().build();
        var second = runtime.packService().build();
        runtime.writePackReport(second);
        helper.assertTrue(first.sha256().equals(second.sha256()),
                "identical Polymer inputs should produce the same SHA-256");
        helper.assertTrue(second.cacheHit(), "second identical build should hit the bounded cache");
        helper.assertTrue(second.entryCount() > 0, "generated resource pack should contain entries");
        helper.assertTrue(first.warnings().isEmpty() && second.warnings().isEmpty(),
                "Polymer should accept every generated resource-pack input without issues");
        helper.succeed();
    }

    @GameTest
    public void administratorCommandsInspectFrozenPlan(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        var source = server.createCommandSourceStack();
        try {
            helper.assertTrue(dispatcher.execute("polymcreborn scan", source) > 0,
                    "scan should inspect the frozen plan");
            helper.assertTrue(dispatcher.execute(
                    "polymcreborn why polymc-reborn-gametest:basic_item", source) > 0,
                    "why should expose the selected decision and candidates");
            helper.assertTrue(dispatcher.execute("polymcreborn report json", source) > 0,
                    "the JSON report command should complete");
            String report = java.nio.file.Files.readString(PolyMcReborn.runtime().configManager()
                    .reportsDirectory().resolve("compatibility-latest.json"));
            helper.assertTrue(report.contains("\"candidates\"")
                            && report.contains("polymc-reborn-gametest:basic_item"),
                    "the JSON report should contain the decision and full candidate trace");
            helper.assertTrue(dispatcher.execute("polymcreborn config validate", source) > 0,
                    "the strict configuration validation command should complete");
            helper.assertTrue(dispatcher.getRoot().getChild("pmcr") != null,
                    "the short command alias should be registered");
            helper.assertTrue(dispatcher.getRoot().getChild("polymc") != null,
                    "the conflict-free legacy command alias should be registered");
            helper.assertTrue(new net.minecraft.SystemReport().toLineSeparatedString()
                            .contains("PolyMc Reborn"),
                    "Minecraft system/crash reports should include the path-free Reborn summary");
            helper.succeed();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | java.io.IOException exception) {
            helper.fail("administrator command failed: " + exception.getMessage());
        }
    }

    private static MappingStatus status(io.github.polymcreborn.mapping.MappingPlan plan,
                                        ContentType type, String path) {
        var decision = plan.decision(type, FixtureContent.MOD_ID + ":" + path);
        return decision == null ? null : decision.status();
    }
}
