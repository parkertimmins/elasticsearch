/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.swisshash;

import org.elasticsearch.test.ESTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class LongLongDirectHashTests extends ESTestCase {

    public void testAddAndFind() {
        try (LongLongDirectHash hash = new LongLongDirectHash()) {
            long a1 = randomLong(), a2 = randomLong();
            long b1 = randomValueOtherThan(a1, ESTestCase::randomLong);
            long b2 = randomLong();

            assertThat("first add returns ordinal 0", hash.add(a1, a2), equalTo(0L));
            assertThat("size increments", hash.size(), equalTo(1L));
            assertThat("find returns ordinal 0", hash.find(a1, a2), equalTo(0L));
            assertThat("getKey1 returns a1", hash.getKey1(0), equalTo(a1));
            assertThat("getKey2 returns a2", hash.getKey2(0), equalTo(a2));

            assertThat("re-add returns -1-0", hash.add(a1, a2), equalTo(-1L));
            assertThat("size unchanged on re-add", hash.size(), equalTo(1L));

            assertThat("second distinct pair gets ordinal 1", hash.add(b1, b2), equalTo(1L));
            assertThat("size increments to 2", hash.size(), equalTo(2L));
            assertThat("find (b1,b2) returns 1", hash.find(b1, b2), equalTo(1L));
        }
    }

    public void testMissingKey() {
        try (LongLongDirectHash hash = new LongLongDirectHash()) {
            long k1 = randomLong(), k2 = randomLong();
            assertThat("find on empty table", hash.find(k1, k2), equalTo(-1L));
            hash.add(k1, k2);
            long o1 = randomValueOtherThan(k1, ESTestCase::randomLong);
            long o2 = randomLong();
            long found = hash.find(o1, o2);
            assertTrue("find for non-added key is -1 or nonneg", found == -1L || found >= 0);
        }
    }

    /**
     * Finds two key pairs that share the same slot (lower 10 bits of their 64-bit hashes match)
     * but carry different fingerprints (upper 32 bits differ). Adding the second pair evicts the
     * first and assigns a fresh ordinal. Verifies:
     * <ul>
     *   <li>The second pair gets a fresh ordinal.</li>
     *   <li>The first pair's old ordinal is still readable from the key store.</li>
     *   <li>{@link LongLongDirectHash#find} returns {@code -1} for the evicted pair.</li>
     * </ul>
     */
    public void testEvictionOnFingerprintMismatch() {
        long[] pair = findSameSlotDifferentFingerprintPair();
        long k1a = pair[0], k2a = pair[1];
        long k1b = pair[2], k2b = pair[3];

        try (LongLongDirectHash hash = new LongLongDirectHash()) {
            long ord1 = hash.add(k1a, k2a);
            assertThat("first pair gets ordinal 0", ord1, equalTo(0L));
            assertThat("first pair findable before eviction", hash.find(k1a, k2a), equalTo(0L));

            long ord2 = hash.add(k1b, k2b);
            assertThat("second pair gets fresh ordinal after eviction", ord2, greaterThan(0L));
            assertThat("size is 2", hash.size(), equalTo(2L));

            assertThat("evicted pair no longer findable", hash.find(k1a, k2a), equalTo(-1L));
            assertThat("ordinal 0 still holds first pair key1", hash.getKey1(0), equalTo(k1a));
            assertThat("ordinal 0 still holds first pair key2", hash.getKey2(0), equalTo(k2a));
            assertThat("ordinal 1 holds second pair key1", hash.getKey1(1), equalTo(k1b));
            assertThat("ordinal 1 holds second pair key2", hash.getKey2(1), equalTo(k2b));
        }
    }

    public void testNoInterferenceAcrossSlots() {
        try (LongLongDirectHash hash = new LongLongDirectHash()) {
            List<long[]> pairs = new ArrayList<>();
            int inserted = 0;
            long attempts = 0;
            while (inserted < 100 && attempts < 10_000) {
                long k1 = randomLong(), k2 = randomLong();
                long result = hash.add(k1, k2);
                if (result >= 0) {
                    pairs.add(new long[] { k1, k2 });
                    inserted++;
                }
                attempts++;
            }
            for (long[] kp : pairs) {
                long found = hash.find(kp[0], kp[1]);
                if (found >= 0) {
                    assertThat("key1 at ordinal matches", hash.getKey1(found), equalTo(kp[0]));
                    assertThat("key2 at ordinal matches", hash.getKey2(found), equalTo(kp[1]));
                }
            }
        }
    }

    public void testClear() {
        try (LongLongDirectHash hash = new LongLongDirectHash()) {
            for (int i = 0; i < 10; i++) {
                hash.add((long) i, (long) -i);
            }
            assertThat(hash.size(), equalTo(10L));

            hash.clear();
            assertThat("size resets after clear", hash.size(), equalTo(0L));
            assertThat("find on cleared table returns -1", hash.find(0L, 0L), equalTo(-1L));
            assertThat("ordinal restarts at 0 after clear", hash.add(1L, 2L), equalTo(0L));
        }
    }

    public void testKeyStoreGrowth() {
        try (LongLongDirectHash hash = new LongLongDirectHash()) {
            int target = LongLongDirectHash.CAPACITY * 3;
            for (int i = 0; i < target; i++) {
                hash.add((long) i * 1_000_003L, (long) i * 999_983L);
            }
            assertThat("size accounts for all assigned ordinals", hash.size(), equalTo((long) target));
            for (int i = 0; i < target; i++) {
                // get() must not throw
                hash.getKey1(i);
                hash.getKey2(i);
            }
        }
    }

    // ---- helpers ----

    /**
     * Returns four longs {k1a, k2a, k1b, k2b} such that (k1a,k2a) and (k1b,k2b) land in the
     * same slot (lower 10 bits of hash64 match) but have different fingerprints (upper 32 bits
     * of hash64 differ).
     */
    private static long[] findSameSlotDifferentFingerprintPair() {
        long[][] firstPair = new long[LongLongDirectHash.CAPACITY][];
        long[] firstHash = new long[LongLongDirectHash.CAPACITY];
        Arrays.fill(firstHash, Long.MIN_VALUE);

        // Vary both keys together to get diverse hashes
        for (long i = 0; i < 500_000L; i++) {
            long k1 = i * 1_000_003L;
            long k2 = i * 999_983L + 7L;
            long h = hash(k1, k2);
            int slot = (int) h & (LongLongDirectHash.CAPACITY - 1);
            if (firstHash[slot] == Long.MIN_VALUE) {
                firstPair[slot] = new long[] { k1, k2 };
                firstHash[slot] = h;
            } else if ((int) (firstHash[slot] >>> 32) != (int) (h >>> 32)) {
                return new long[] { firstPair[slot][0], firstPair[slot][1], k1, k2 };
            }
        }
        throw new IllegalStateException("could not find same-slot different-fingerprint pair");
    }

    private static long hash(long key1, long key2) {
        long h = key1 * 0x9E3779B97F4A7C15L ^ key2;
        h = (h ^ (h >>> 32)) * 0x4cd6944c5cc20b6dL;
        h = (h ^ (h >>> 29)) * 0xfc12c5b19d3259e9L;
        return h ^ (h >>> 32);
    }
}
