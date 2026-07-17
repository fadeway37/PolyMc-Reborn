/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend;

import io.github.polymcreborn.api.ContentDescriptor;

/** Future explicit entity projection SPI; the MVP only classifies support. */
public interface EntityProjectionBackend {
    String id();

    Classification classify(ContentDescriptor entityType);

    record Classification(boolean supported, String strategy, String reason) {
    }
}
