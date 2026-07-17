/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.backend;

import io.github.polymcreborn.api.ContentDescriptor;

/** Future transaction-validated GUI projection SPI; the MVP only classifies support. */
public interface GuiProjectionBackend {
    String id();

    Classification classify(ContentDescriptor menuType);

    record Classification(boolean supported, String strategy, String reason) {
    }
}
