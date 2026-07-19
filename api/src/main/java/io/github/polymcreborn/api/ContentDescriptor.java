/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.api;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/** Immutable, backend-neutral description of one registered server object and its analyzed facts. */
public record ContentDescriptor(
        String registryId,
        String ownerMod,
        ContentType contentType,
        SortedMap<String, String> attributes) implements Comparable<ContentDescriptor> {

    public ContentDescriptor {
        registryId = requireIdentifier(registryId);
        ownerMod = requireToken(ownerMod, "ownerMod");
        contentType = Objects.requireNonNull(contentType, "contentType");
        var stable = new TreeMap<String, String>();
        Objects.requireNonNull(attributes, "attributes").forEach((key, value) ->
                stable.put(requireToken(key, "attribute key"), Objects.requireNonNull(value, "attribute value")));
        attributes = Collections.unmodifiableSortedMap(stable);
    }

    public static ContentDescriptor of(String registryId, String ownerMod, ContentType type,
                                       Map<String, String> attributes) {
        return new ContentDescriptor(registryId, ownerMod, type, new TreeMap<>(attributes));
    }

    public String key() {
        var state = attributes.get("state");
        return contentType.name().toLowerCase() + ":" + registryId + (state == null ? "" : "[" + state + "]");
    }

    public boolean booleanAttribute(String name) {
        return Boolean.parseBoolean(attributes.getOrDefault(name, "false"));
    }

    @Override
    public int compareTo(ContentDescriptor other) {
        int byId = registryId.compareTo(other.registryId);
        if (byId != 0) {
            return byId;
        }
        int byType = contentType.compareTo(other.contentType);
        return byType != 0 ? byType : key().compareTo(other.key());
    }

    private static String requireIdentifier(String value) {
        value = requireToken(value, "registryId");
        int separator = value.indexOf(':');
        if (separator < 1 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("registryId must be a namespaced identifier: " + value);
        }
        return value;
    }

    private static String requireToken(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no whitespace");
        }
        return value;
    }
}
