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
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.MockBigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class BytesRefDirectHashTests extends ESTestCase {

    private BigArrays bigArrays() {
        PageCacheRecycler recycler = new PageCacheRecycler(Settings.EMPTY);
        return new MockBigArrays(recycler, ByteSizeValue.ofBytes(Long.MAX_VALUE));
    }

    public void testAddAndFind() {
        try (BytesRefDirectHash hash = new BytesRefDirectHash(bigArrays())) {
            BytesRef a = new BytesRef("hello");
            BytesRef b = new BytesRef("world");
            BytesRef scratch = new BytesRef();

            assertThat("first add returns ordinal 0", hash.add(a), equalTo(0L));
            assertThat("size increments", hash.size(), equalTo(1L));
            assertThat("find returns ordinal 0", hash.find(a), equalTo(0L));
            assertThat("get returns key a", hash.get(0, scratch), equalTo(a));

            assertThat("re-add returns -1-0", hash.add(a), equalTo(-1L));
            assertThat("size unchanged on re-add", hash.size(), equalTo(1L));

            assertThat("second distinct key gets ordinal 1", hash.add(b), equalTo(1L));
            assertThat("size increments to 2", hash.size(), equalTo(2L));
            assertThat("find b returns 1", hash.find(b), equalTo(1L));
            assertThat("get returns key b", hash.get(1, scratch), equalTo(b));
        }
    }

    public void testMissingKey() {
        try (BytesRefDirectHash hash = new BytesRefDirectHash(bigArrays())) {
            BytesRef key = new BytesRef("absent");
            assertThat("find on empty table", hash.find(key), equalTo(-1L));
        }
    }

    public void testEmptyBytesRef() {
        try (BytesRefDirectHash hash = new BytesRefDirectHash(bigArrays())) {
            BytesRef empty = new BytesRef();
            assertThat("empty BytesRef gets ordinal 0", hash.add(empty), equalTo(0L));
            assertThat("re-add empty BytesRef returns -1", hash.add(empty), equalTo(-1L));
            assertThat("find empty BytesRef returns 0", hash.find(empty), equalTo(0L));
        }
    }

    /**
     * Finds two keys that land in the same slot (lower 10 bits of hash64 match) but with
     * different fingerprints (upper 32 bits differ). Adding the second evicts the first.
     */
    public void testEvictionOnFingerprintMismatch() {
        BytesRef[] pair = findSameSlotDifferentFingerprintPair();
        BytesRef key1 = pair[0];
        BytesRef key2 = pair[1];

        try (BytesRefDirectHash hash = new BytesRefDirectHash(bigArrays())) {
            long ord1 = hash.add(key1);
            assertThat("key1 gets ordinal 0", ord1, equalTo(0L));
            assertThat("key1 findable before eviction", hash.find(key1), equalTo(0L));

            long ord2 = hash.add(key2);
            assertThat("key2 gets fresh ordinal after eviction", ord2, greaterThan(0L));
            assertThat("size is 2", hash.size(), equalTo(2L));

            assertThat("evicted key1 no longer findable", hash.find(key1), equalTo(-1L));
            BytesRef scratch = new BytesRef();
            assertThat("ordinal 0 still holds key1 in store", hash.get(0, scratch), equalTo(key1));
            assertThat("ordinal 1 holds key2", hash.get(1, scratch), equalTo(key2));
        }
    }

    public void testNoInterferenceAcrossSlots() {
        try (BytesRefDirectHash hash = new BytesRefDirectHash(bigArrays())) {
            List<BytesRef> keys = new ArrayList<>();
            int inserted = 0;
            int attempts = 0;
            while (inserted < 100 && attempts < 10_000) {
                BytesRef key = new BytesRef(randomAlphaOfLengthBetween(1, 20));
                long result = hash.add(key);
                if (result >= 0) {
                    keys.add(BytesRef.deepCopyOf(key));
                    inserted++;
                }
                attempts++;
            }
            BytesRef scratch = new BytesRef();
            for (BytesRef key : keys) {
                long found = hash.find(key);
                if (found >= 0) {
                    assertThat("found key matches", hash.get(found, scratch), equalTo(key));
                }
            }
        }
    }

    public void testGetBytesRefs() {
        try (BytesRefDirectHash hash = new BytesRefDirectHash(bigArrays())) {
            BytesRef a = new BytesRef("alpha");
            BytesRef b = new BytesRef("beta");
            hash.add(a);
            hash.add(b);
            assertThat("getBytesRefs returns store with 2 entries", hash.getBytesRefs().size(), equalTo(2L));
        }
    }

    public void testKeyStoreGrowth() {
        try (BytesRefDirectHash hash = new BytesRefDirectHash(bigArrays())) {
            int target = BytesRefDirectHash.CAPACITY * 3;
            for (int i = 0; i < target; i++) {
                hash.add(new BytesRef(Integer.toString(i)));
            }
            assertThat("size accounts for all ordinals including evicted", hash.size(), equalTo((long) target));
            BytesRef scratch = new BytesRef();
            for (int i = 0; i < target; i++) {
                assertNotNull("key at ordinal " + i + " is non-null", hash.get(i, scratch));
            }
        }
    }

    // ---- helpers ----

    /**
     * Returns two BytesRef keys whose hash64 values land in the same slot (lower 10 bits match)
     * but whose fingerprints (upper 32 bits of hash64) differ.
     */
    private static BytesRef[] findSameSlotDifferentFingerprintPair() {
        BytesRef[] firstKey = new BytesRef[BytesRefDirectHash.CAPACITY];
        long[] firstHash = new long[BytesRefDirectHash.CAPACITY];
        Arrays.fill(firstHash, Long.MIN_VALUE);

        for (int i = 0; i < 1_000_000; i++) {
            BytesRef key = new BytesRef(Integer.toString(i));
            long h = BytesRefSwissHash.hash64(key);
            int slot = (int) h & (BytesRefDirectHash.CAPACITY - 1);
            if (firstHash[slot] == Long.MIN_VALUE) {
                firstKey[slot] = BytesRef.deepCopyOf(key);
                firstHash[slot] = h;
            } else if ((int) (firstHash[slot] >>> 32) != (int) (h >>> 32)) {
                return new BytesRef[] { firstKey[slot], key };
            }
        }
        throw new IllegalStateException("could not find same-slot different-fingerprint pair");
    }

}
