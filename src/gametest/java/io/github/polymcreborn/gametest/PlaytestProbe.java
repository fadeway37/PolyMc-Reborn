/* SPDX-License-Identifier: LGPL-3.0-or-later */
package io.github.polymcreborn.gametest;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Cross-component observations written by the server-only production playtest fixture. */
public final class PlaytestProbe {
    public static final AtomicInteger JOIN_COUNT = new AtomicInteger();
    public static final AtomicInteger DISCONNECT_COUNT = new AtomicInteger();
    public static final AtomicInteger GUI_OPEN_COUNT = new AtomicInteger();
    public static final AtomicInteger GUI_CLOSE_COUNT = new AtomicInteger();
    public static final AtomicInteger PROPERTY_GUI_OPEN_COUNT = new AtomicInteger();
    public static final AtomicInteger PROPERTY_TICK_COUNT = new AtomicInteger();
    public static final AtomicInteger PROPERTY_COMPLETION_COUNT = new AtomicInteger();
    public static final AtomicInteger ENTITY_USE_COUNT = new AtomicInteger();
    public static final AtomicInteger ENTITY_ATTACK_COUNT = new AtomicInteger();
    public static final AtomicInteger DIMENSION_CHANGE_COUNT = new AtomicInteger();
    public static final AtomicInteger COMMAND_COUNT = new AtomicInteger();
    public static final AtomicInteger RESOURCE_PACK_PUSH_COUNT = new AtomicInteger();
    public static final AtomicInteger RESOURCE_PACK_REQUEST_COUNT = new AtomicInteger();
    public static final AtomicInteger MAX_TOOL_DAMAGE = new AtomicInteger();
    public static final AtomicInteger FOOD_REMAINING = new AtomicInteger(-1);
    public static final AtomicInteger BASIC_ITEM_REMAINING = new AtomicInteger(-1);
    public static final AtomicInteger SOAK_GUI_CYCLES = new AtomicInteger();
    public static final AtomicInteger SOAK_REJECTED_TRANSACTIONS = new AtomicInteger();
    public static final AtomicInteger SOAK_ENTITY_SPAWNS = new AtomicInteger();
    public static final AtomicInteger SOAK_ENTITY_DESPAWNS = new AtomicInteger();
    public static final AtomicInteger SOAK_TRACKING_CYCLES = new AtomicInteger();
    public static final AtomicInteger SUPPORT_BUNDLE_GENERATIONS = new AtomicInteger();
    public static final AtomicInteger MAPPING_DRY_RUNS = new AtomicInteger();
    public static final AtomicLong REJECTED_TRANSACTION_TOTAL_NANOS = new AtomicLong();
    public static final AtomicLong REJECTED_TRANSACTION_MAX_NANOS = new AtomicLong();
    public static final AtomicLong SERVER_TICKS = new AtomicLong();
    public static volatile boolean placedBlockObserved;
    public static volatile boolean brokenBlockObserved;
    public static volatile boolean simpleBlockPlacedObserved;
    public static volatile boolean simpleBlockBrokenObserved;
    public static volatile boolean guiInventoryIntegrity;
    public static volatile boolean semanticUseObserved;
    public static volatile boolean stateToggleObserved;
    public static volatile boolean itemDropObserved;
    public static volatile boolean itemPickupObserved;

    private PlaytestProbe() {
    }
}
