/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import net.minecraft.core.RegistrySynchronization;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class DynamicRegistrySanitizerTest {
    @Test
    void removesOnlyEntriesThatReferenceAHiddenStaticIdentifier() {
        var blocked = new CompoundTag();
        var effects = new CompoundTag();
        effects.put("example:custom_effect", new ListTag());
        blocked.put("effects", effects);
        var safe = new CompoundTag();
        safe.putString("description", "example translation text is harmless");
        var entries = List.of(entry("example:blocked", blocked), entry("example:safe", safe));

        var filtered = DynamicRegistrySanitizer.filterEntries(Registries.ENCHANTMENT, entries,
                Set.of("example:custom_effect"), null);

        assertEquals(List.of(entry("example:safe", safe)), filtered);
    }

    @Test
    void detectsHiddenIdentifiersNestedInListsAndReturnsOriginalWhenSafe() {
        var blocked = new CompoundTag();
        var list = new ListTag();
        list.add(StringTag.valueOf("example:hidden_type"));
        blocked.put("nested", list);
        var blockedEntries = List.of(entry("example:blocked", blocked));
        assertEquals(List.of(), DynamicRegistrySanitizer.filterEntries(Registries.ENCHANTMENT,
                blockedEntries, Set.of("example:hidden_type"), null));

        var safeEntries = List.of(entry("example:safe", new CompoundTag()));
        assertSame(safeEntries, DynamicRegistrySanitizer.filterEntries(Registries.ENCHANTMENT,
                safeEntries, Set.of("example:hidden_type"), null));
    }

    @Test
    void recipeBookFilteringIsLimitedToHiddenDisplayAndCategoryTypes() {
        var vanillaDisplay = Identifier.parse("minecraft:crafting_shaped");
        var vanillaCategory = Identifier.parse("minecraft:crafting");
        assertNull(RecipeBookSanitizer.blockedType(vanillaDisplay, vanillaCategory,
                Set.of("example:cooking")));
        assertEquals("recipe display type example:cooking",
                RecipeBookSanitizer.blockedType(Identifier.parse("example:cooking"), vanillaCategory,
                        Set.of("example:cooking")));
        assertEquals("recipe book category example:meals",
                RecipeBookSanitizer.blockedType(vanillaDisplay, Identifier.parse("example:meals"),
                        Set.of("example:meals")));
    }

    private static RegistrySynchronization.PackedRegistryEntry entry(String id, CompoundTag tag) {
        return new RegistrySynchronization.PackedRegistryEntry(Identifier.parse(id), Optional.of(tag));
    }
}
