/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.config;

import java.nio.file.Path;

/** Configuration error retaining the operator-visible file and JSON path. */
public final class ConfigurationException extends RuntimeException {
    private final Path file;
    private final String jsonPath;

    public ConfigurationException(Path file, String jsonPath, String message) {
        super(file + " " + jsonPath + ": " + message);
        this.file = file;
        this.jsonPath = jsonPath;
    }

    public ConfigurationException(Path file, String jsonPath, String message, Throwable cause) {
        super(file + " " + jsonPath + ": " + message, cause);
        this.file = file;
        this.jsonPath = jsonPath;
    }

    public Path file() {
        return file;
    }

    public String jsonPath() {
        return jsonPath;
    }
}
