/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.swisshash;

import org.elasticsearch.common.util.LongLongHashTable;

import java.util.Arrays;

/**
 * A direct-mapped hash table for (long, long) key pairs, intended for the INITIAL phase of
 * ES|QL aggregations.
 *
 * <p>The table has exactly {@link #CAPACITY} (1024) slots. Each slot holds a single {@code long}
 * encoding {@code (fingerprint32 << 32) | ordinal}, where {@code fingerprint32} is the upper 32
 * bits of the 64-bit hash of the key pair and {@code ordinal} is the group-id assigned when the
 * entry was inserted. The sentinel value {@code -1L} marks an empty slot.
 *
 * <p>Collision resolution is deliberately absent. When {@link #add} finds the target slot occupied
 * by a different key pair, the existing entry is <em>evicted</em>: the slot is overwritten and the
 * new pair is assigned a fresh ordinal. The FINAL aggregation phase reconciles any duplicates
 * produced by eviction.
 *
 * <p>This class does not integrate with the circuit breaker. Callers that need memory accounting
 * may use {@link #RAM_BYTES_USED} as a fixed upper bound.
 */
public final class LongLongDirectHash implements LongLongHashTable {

    /** Number of slots in the direct table. Must be a power of two. */
    public static final int CAPACITY = 1024;

    private static final int MASK = CAPACITY - 1;

    private static final int INITIAL_KEY_STORE_CAPACITY = CAPACITY;

    /** Approximate heap cost of a fresh instance (slots + initial key stores). */
    public static final long RAM_BYTES_USED = (long) CAPACITY * Long.BYTES + 2L * INITIAL_KEY_STORE_CAPACITY * Long.BYTES;

    /** Sentinel: empty slot. */
    private static final long EMPTY = -1L;

    private final long[] slots = new long[CAPACITY];
    private long[] key1s = new long[INITIAL_KEY_STORE_CAPACITY];
    private long[] key2s = new long[INITIAL_KEY_STORE_CAPACITY];
    private int size;

    public LongLongDirectHash() {
        Arrays.fill(slots, EMPTY);
    }

    private static long hash(long key1, long key2) {
        long h = key1 * 0x9E3779B97F4A7C15L ^ key2;
        h = (h ^ (h >>> 32)) * 0x4cd6944c5cc20b6dL;
        h = (h ^ (h >>> 29)) * 0xfc12c5b19d3259e9L;
        return h ^ (h >>> 32);
    }

    /**
     * Adds the key pair {@code (key1, key2)}. If the pair is the live entry at its home slot,
     * returns {@code -(ordinal) - 1}. Otherwise assigns a fresh ordinal, stores the keys, and
     * overwrites the slot (evicting any previous occupant), then returns the new ordinal.
     */
    @Override
    public long add(long key1, long key2) {
        final long hash64 = hash(key1, key2);
        final int fingerprint = (int) (hash64 >>> 32);
        final int slotIdx = (int) hash64 & MASK;
        final long existing = slots[slotIdx];
        if (existing != EMPTY && (int) (existing >>> 32) == fingerprint) {
            final int ordinal = (int) existing;
            if (key1s[ordinal] == key1 && key2s[ordinal] == key2) {
                return -1L - ordinal;
            }
        }
        final int ordinal = size;
        if (ordinal >= key1s.length) {
            key1s = Arrays.copyOf(key1s, key1s.length * 2);
            key2s = Arrays.copyOf(key2s, key2s.length * 2);
        }
        key1s[ordinal] = key1;
        key2s[ordinal] = key2;
        size++;
        slots[slotIdx] = ((long) fingerprint << 32) | Integer.toUnsignedLong(ordinal);
        return ordinal;
    }

    /**
     * Returns the ordinal of {@code (key1, key2)} if it is the live entry at its slot, or
     * {@code -1} otherwise. A result of {@code -1} does not imply the pair was never added; it
     * may have been evicted.
     */
    @Override
    public long find(long key1, long key2) {
        final long hash64 = hash(key1, key2);
        final int fingerprint = (int) (hash64 >>> 32);
        final int slotIdx = (int) hash64 & MASK;
        final long existing = slots[slotIdx];
        if (existing == EMPTY) {
            return -1;
        }
        if ((int) (existing >>> 32) == fingerprint) {
            final int ordinal = (int) existing;
            if (key1s[ordinal] == key1 && key2s[ordinal] == key2) {
                return ordinal;
            }
        }
        return -1;
    }

    @Override
    public long getKey1(long id) {
        return key1s[Math.toIntExact(id)];
    }

    @Override
    public long getKey2(long id) {
        return key2s[Math.toIntExact(id)];
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public long ramBytesUsed() {
        return (long) CAPACITY * Long.BYTES + (long) key1s.length * Long.BYTES + (long) key2s.length * Long.BYTES;
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
