/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.swisshash;

import org.elasticsearch.common.util.LongHashTable;

import java.util.Arrays;

/**
 * A direct-mapped hash table for {@code long} keys, intended for the INITIAL phase of ES|QL
 * aggregations.
 *
 * <p>The table has exactly {@link #CAPACITY} (1024) slots. Each slot holds a single {@code long}
 * encoding {@code (hash32 << 32) | ordinal}, where {@code hash32} is the full 32-bit hash of the
 * key and {@code ordinal} is the group-id assigned when the entry was inserted. The sentinel value
 * {@code -1L} marks an empty slot.
 *
 * <p>Collision resolution is deliberately absent. When {@link #add} finds the target slot occupied
 * by a <em>different</em> hash, the existing entry is <em>evicted</em>: the slot is overwritten
 * and the new key is assigned a fresh ordinal. The evicted key's old ordinal remains live; callers
 * in the FINAL aggregation phase must reconcile any eviction duplicates.
 *
 * <p>This class does not integrate with the circuit breaker. Callers that need memory accounting
 * may use {@link #RAM_BYTES_USED} as a fixed upper bound.
 */
public final class LongDirectHash implements LongHashTable {

    /** Number of slots in the direct table. Must be a power of two. */
    public static final int CAPACITY = 1024;

    private static final int MASK = CAPACITY - 1;

    private static final int INITIAL_KEY_STORE_CAPACITY = CAPACITY;

    /** Approximate heap cost of a fresh instance (slots + initial key store). */
    public static final long RAM_BYTES_USED = (long) CAPACITY * Long.BYTES + (long) INITIAL_KEY_STORE_CAPACITY * Long.BYTES;

    /** Sentinel: empty slot. */
    private static final long EMPTY = -1L;

    /**
     * The direct table. Each live entry encodes {@code (hash32 << 32) | ordinal}.
     */
    private final long[] slots = new long[CAPACITY];

    /**
     * Dense key store indexed by ordinal, in insertion order. Grows on demand.
     */
    private long[] keys = new long[INITIAL_KEY_STORE_CAPACITY];

    /** Total ordinals assigned so far (includes ordinals for evicted entries). */
    private int size;

    public LongDirectHash() {
        Arrays.fill(slots, EMPTY);
    }

    /**
     * Adds {@code key}. If the key is the live entry at its home slot, returns
     * {@code -(ordinal) - 1}. Otherwise assigns a fresh ordinal, stores the key, and
     * overwrites the slot (evicting any previous occupant), then returns the new ordinal.
     */
    @Override
    public long add(long key) {
        final int hash32 = LongSwissHash.hash(key);
        final int slotIdx = hash32 & MASK;
        final long existing = slots[slotIdx];
        if (existing != EMPTY && (int) (existing >>> 32) == hash32) {
            final int storedOrdinal = (int) existing;
            if (keys[storedOrdinal] == key) {
                return -1L - storedOrdinal;
            }
        }
        final int ordinal = size;
        if (ordinal >= keys.length) {
            keys = Arrays.copyOf(keys, keys.length * 2);
        }
        keys[ordinal] = key;
        size++;
        slots[slotIdx] = ((long) hash32 << 32) | Integer.toUnsignedLong(ordinal);
        return ordinal;
    }

    /**
     * Returns the ordinal of {@code key} if it is the live entry at its slot, or {@code -1}
     * otherwise. A result of {@code -1} does not imply the key was never added; it may have
     * been evicted.
     */
    @Override
    public long find(long key) {
        final int hash32 = LongSwissHash.hash(key);
        final int slotIdx = hash32 & MASK;
        final long existing = slots[slotIdx];
        if (existing == EMPTY) {
            return -1;
        }
        if ((int) (existing >>> 32) == hash32) {
            final int storedOrdinal = (int) existing;
            if (keys[storedOrdinal] == key) {
                return storedOrdinal;
            }
        }
        return -1;
    }

    /**
     * Returns the key stored at the given ordinal. The ordinal must have been returned by
     * a previous {@link #add} call (including ordinals for evicted entries).
     */
    @Override
    public long get(long id) {
        return keys[Math.toIntExact(id)];
    }

    @Override
    public long size() {
        return size;
    }

    /** Resets all slots and the key store, discarding all entries. */
    public void clear() {
        Arrays.fill(slots, EMPTY);
        size = 0;
    }

    @Override
    public void close() {
        clear();
    }
}
