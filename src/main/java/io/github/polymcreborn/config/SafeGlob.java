/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

/** Bounded wildcard matcher implemented with dynamic programming, never a regular expression. */
public final class SafeGlob {
    private static final int MAX_LENGTH = 256;
    private final String pattern;

    private SafeGlob(String pattern) {
        this.pattern = pattern;
    }

    public static SafeGlob compile(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Glob must contain 1.." + MAX_LENGTH + " characters");
        }
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (!(Character.isLowerCase(character) || Character.isDigit(character)
                    || "_./:-*?".indexOf(character) >= 0)) {
                throw new IllegalArgumentException("Unsafe glob character '" + character + "'");
            }
        }
        return new SafeGlob(pattern);
    }

    public boolean matches(String value) {
        if (value == null || value.length() > 1024) {
            return false;
        }
        boolean[] previous = new boolean[value.length() + 1];
        previous[0] = true;
        for (int patternIndex = 0; patternIndex < pattern.length(); patternIndex++) {
            char token = pattern.charAt(patternIndex);
            boolean[] current = new boolean[value.length() + 1];
            if (token == '*') {
                current[0] = previous[0];
                for (int valueIndex = 1; valueIndex <= value.length(); valueIndex++) {
                    current[valueIndex] = previous[valueIndex] || current[valueIndex - 1];
                }
            } else {
                for (int valueIndex = 1; valueIndex <= value.length(); valueIndex++) {
                    current[valueIndex] = previous[valueIndex - 1]
                            && (token == '?' || token == value.charAt(valueIndex - 1));
                }
            }
            previous = current;
        }
        return previous[value.length()];
    }

    @Override
    public String toString() {
        return pattern;
    }
}
