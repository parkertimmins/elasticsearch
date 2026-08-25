/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.swisshash;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.bytes.PagedBytesCursor;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.BytesRefArray;
import org.elasticsearch.common.util.BytesRefHashTable;

import java.util.Arrays;

/**
 * A direct-mapped hash table for {@link BytesRef} keys, intended for the INITIAL phase of
 * ES|QL aggregations.
 *
 * <p>The table has exactly {@link #CAPACITY} (1024) slots. Each slot holds a single {@code long}
 * encoding {@code (fingerprint32 << 32) | ordinal}, where {@code fingerprint32} is the upper 32
 * bits of the 64-bit hash of the key and {@code ordinal} is the group-id assigned when the entry
 * was inserted. The sentinel value {@code -1L} marks an empty slot.
 *
 * <p>Collision resolution is deliberately absent. When {@link #add(BytesRef)} finds the target
 * slot occupied by a different key, the existing entry is <em>evicted</em>: the slot is
 * overwritten and the new key is assigned a fresh ordinal. The FINAL aggregation phase reconciles
 * any duplicates produced by eviction.
 */
public final class BytesRefDirectHash implements BytesRefHashTable {

    /** Number of slots in the direct table. Must be a power of two. */
    public static final int CAPACITY = 1024;

    private static final int MASK = CAPACITY - 1;

    /** Sentinel: empty slot. */
    private static final long EMPTY = -1L;

    private final long[] slots = new long[CAPACITY];

    /** Dense key store indexed by ordinal. */
    private final BytesRefArray keys;

    private int size;
    private final BytesRef scratch = new BytesRef();
    private final PagedBytesCursor cursorScratch = new PagedBytesCursor();

    public BytesRefDirectHash(BigArrays bigArrays) {
        this.keys = new BytesRefArray(CAPACITY, bigArrays);
        Arrays.fill(slots, EMPTY);
    }

    /**
     * Adds {@code key}. If the key is the live entry at its home slot, returns
     * {@code -(ordinal) - 1}. Otherwise assigns a fresh ordinal, stores the key, and
     * overwrites the slot (evicting any previous occupant), then returns the new ordinal.
     */
    @Override
    public long add(BytesRef key) {
        final long hash64 = BytesRefSwissHash.hash64(key);
        final int fingerprint = (int) (hash64 >>> 32);
        final int slotIdx = (int) hash64 & MASK;
        final long existing = slots[slotIdx];
        if (existing != EMPTY && (int) (existing >>> 32) == fingerprint) {
            final int ordinal = (int) existing;
            if (keys.bytesEqual(ordinal, key)) {
                return -1L - ordinal;
            }
        }
        final int ordinal = size;
        keys.append(key);
        size++;
        slots[slotIdx] = ((long) fingerprint << 32) | Integer.toUnsignedLong(ordinal);
        return ordinal;
    }

    /**
     * Adds the key supplied by {@code key}. If the key is the live entry at its home slot,
     * returns {@code -(ordinal) - 1} without draining the cursor. Otherwise assigns a fresh
     * ordinal, drains the cursor into the key store, and overwrites the slot, then returns
     * the new ordinal.
     */
    @Override
    public long add(PagedBytesCursor key) {
        final long hash64 = BytesRefSwissHash.hash64(key);
        final int fingerprint = (int) (hash64 >>> 32);
        final int slotIdx = (int) hash64 & MASK;
        final long existing = slots[slotIdx];
        if (existing != EMPTY && (int) (existing >>> 32) == fingerprint) {
            final int ordinal = (int) existing;
            if (key.equals(keys.get(ordinal, cursorScratch))) {
                return -1L - ordinal;
            }
        }
        final int ordinal = size;
        keys.append(key);
        size++;
        slots[slotIdx] = ((long) fingerprint << 32) | Integer.toUnsignedLong(ordinal);
        return ordinal;
    }

    /**
     * Returns the ordinal of {@code key} if it is the live entry at its slot, or {@code -1}
     * otherwise. A result of {@code -1} does not imply the key was never added; it may have
     * been evicted.
     */
    @Override
    public long find(BytesRef key) {
        final long hash64 = BytesRefSwissHash.hash64(key);
        final int fingerprint = (int) (hash64 >>> 32);
        final int slotIdx = (int) hash64 & MASK;
        final long existing = slots[slotIdx];
        if (existing == EMPTY) {
            return -1;
        }
        if ((int) (existing >>> 32) == fingerprint) {
            final int ordinal = (int) existing;
            if (keys.bytesEqual(ordinal, key)) {
                return ordinal;
            }
        }
        return -1;
    }

    @Override
    public BytesRef get(long id, BytesRef dest) {
        return keys.get(id, dest);
    }

    @Override
    public BytesRefArray getBytesRefs() {
        return keys;
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public long ramBytesUsed() {
        return (long) CAPACITY * Long.BYTES + keys.ramBytesUsed();
    }

    @Override
    public void close() {
        keys.close();
    }
}
