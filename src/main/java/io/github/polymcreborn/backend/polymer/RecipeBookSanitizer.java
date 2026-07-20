/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Filters recipe-book displays whose wire types are intentionally server-only. */
public final class RecipeBookSanitizer {
    private RecipeBookSanitizer() {
    }

    public static List<ClientboundRecipeBookAddPacket.Entry> filter(
            List<ClientboundRecipeBookAddPacket.Entry> entries) {
        var hidden = DynamicRegistrySanitizer.hiddenStaticIdentifiers();
        if (hidden.isEmpty()) {
            return entries;
        }
        var safe = new ArrayList<ClientboundRecipeBookAddPacket.Entry>(entries.size());
        boolean changed = false;
        for (var entry : entries) {
            Identifier displayType = BuiltInRegistries.RECIPE_DISPLAY.getKey(entry.contents().display().type());
            Identifier category = BuiltInRegistries.RECIPE_BOOK_CATEGORY.getKey(entry.contents().category());
            String blocked = blockedType(displayType, category, hidden);
            if (blocked == null) {
                safe.add(entry);
                continue;
            }
            changed = true;
            String displayId = Integer.toString(entry.contents().id().index());
            DynamicRegistrySanitizer.reportOnce("recipe-book.display.filtered", displayId,
                    "minecraft:recipe_display",
                    "Excluded recipe display " + displayId + " because its " + blocked
                            + " is server-only for vanilla clients");
        }
        return changed ? List.copyOf(safe) : entries;
    }

    static String blockedType(Identifier displayType, Identifier category, Set<String> hidden) {
        if (displayType == null) {
            return "unregistered recipe display type";
        }
        if (hidden.contains(displayType.toString())) {
            return "recipe display type " + displayType;
        }
        if (category == null) {
            return "unregistered recipe book category";
        }
        if (hidden.contains(category.toString())) {
            return "recipe book category " + category;
        }
        return null;
    }
}
