/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend.polymer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Authenticates the minimal marker format reserved for a future opt-in creative reverse path. */
public final class CreativeReverseMappingGuard {
    private static final Set<String> ALLOWED_COMPONENTS = Set.of(
            "custom_name", "lore", "damage", "max_damage", "glint", "count");
    private final byte[] secret;

    public CreativeReverseMappingGuard(byte[] secret) {
        if (secret == null || secret.length < 32) {
            throw new IllegalArgumentException("Reverse mapping secret must contain at least 256 bits");
        }
        this.secret = secret.clone();
    }

    public Marker issue(String targetRegistryId, Map<String, String> components) {
        validateTarget(targetRegistryId);
        var canonicalComponents = validateComponents(components);
        return new Marker(1, targetRegistryId, canonicalComponents,
                HexFormat.of().formatHex(sign(canonical(targetRegistryId, canonicalComponents))));
    }

    public Optional<String> verify(Marker marker) {
        try {
            if (marker == null || marker.schemaVersion() != 1) {
                return Optional.empty();
            }
            validateTarget(marker.targetRegistryId());
            var components = validateComponents(marker.components());
            byte[] supplied = HexFormat.of().parseHex(marker.signature());
            byte[] expected = sign(canonical(marker.targetRegistryId(), components));
            return MessageDigest.isEqual(expected, supplied)
                    ? Optional.of(marker.targetRegistryId()) : Optional.empty();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static TreeMap<String, String> validateComponents(Map<String, String> components) {
        var stable = new TreeMap<String, String>();
        if (components == null || components.size() > ALLOWED_COMPONENTS.size()) {
            throw new IllegalArgumentException("Invalid reverse mapping component set");
        }
        for (var entry : components.entrySet()) {
            if (!ALLOWED_COMPONENTS.contains(entry.getKey()) || entry.getValue() == null
                    || entry.getValue().length() > 4096) {
                throw new IllegalArgumentException("Disallowed reverse mapping component " + entry.getKey());
            }
            stable.put(entry.getKey(), entry.getValue());
        }
        return stable;
    }

    private static void validateTarget(String target) {
        if (target == null || target.length() > 256 || IdentifierValidation.invalid(target)) {
            throw new IllegalArgumentException("Invalid target registry id");
        }
    }

    private byte[] sign(byte[] canonical) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(canonical);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("JVM has no HmacSHA256", exception);
        }
    }

    private static byte[] canonical(String target, Map<String, String> components) {
        var value = new StringBuilder(target).append('\n');
        components.forEach((key, component) -> value.append(key).append('=').append(component).append('\n'));
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    public record Marker(int schemaVersion, String targetRegistryId, Map<String, String> components,
                         String signature) {
        public Marker {
            components = Map.copyOf(new TreeMap<>(components));
        }
    }

    private static final class IdentifierValidation {
        private IdentifierValidation() {
        }

        static boolean invalid(String value) {
            int separator = value.indexOf(':');
            if (separator < 1 || separator == value.length() - 1 || value.indexOf(':', separator + 1) >= 0) {
                return true;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (!(character >= 'a' && character <= 'z') && !(character >= '0' && character <= '9')
                        && "_./:-".indexOf(character) < 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
