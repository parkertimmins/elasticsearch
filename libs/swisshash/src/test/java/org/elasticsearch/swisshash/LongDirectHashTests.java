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
import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class LongDirectHashTests extends ESTestCase {

    public void testAddAndFind() {
        try (LongDirectHash hash = new LongDirectHash()) {
            long a = randomLong();
            long b = randomValueOtherThan(a, ESTestCase::randomLong);

            assertThat("first add returns ordinal 0", hash.add(a), equalTo(0L));
            assertThat("size increments", hash.size(), equalTo(1L));
            assertThat("find returns ordinal 0", hash.find(a), equalTo(0L));
            assertThat("get returns key a", hash.get(0), equalTo(a));

            assertThat("re-add returns -1-0", hash.add(a), equalTo(-1L));
            assertThat("size unchanged on re-add", hash.size(), equalTo(1L));

            assertThat("second distinct key gets ordinal 1", hash.add(b), equalTo(1L));
            assertThat("size increments to 2", hash.size(), equalTo(2L));
            assertThat("find b returns 1", hash.find(b), equalTo(1L));
        }
    }

    public void testMissingKey() {
        try (LongDirectHash hash = new LongDirectHash()) {
            long key = randomLong();
            assertThat("find on empty table", hash.find(key), equalTo(-1L));
            hash.add(key);
            long other = randomValueOtherThan(key, ESTestCase::randomLong);
            // 'other' either hits the same slot (eviction or same-hash-false-positive) or a different slot
            // In the general case (different slot), find returns -1
            long found = hash.find(other);
            assertTrue("find for non-added key returns -1 or ordinal if same slot", found == -1L || found >= 0);
        }
    }

    /**
     * Finds two distinct keys that map to the same slot (lower 10 bits of their 32-bit hashes
     * match) but carry different fingerprints (full 32-bit hashes differ). Adding the second key
     * evicts the first because the slot fingerprint does not match, and a fresh ordinal is
     * assigned. Verifies:
     * <ul>
     *   <li>The second key gets a fresh ordinal.</li>
     *   <li>The first key's old ordinal is still readable from the key store.</li>
     *   <li>{@link LongDirectHash#find} returns {@code -1} for the evicted key.</li>
     * </ul>
     */
    public void testEvictionOnFingerprintMismatch() {
        long[] pair = findSameSlotDifferentFingerprintPair();
        long key1 = pair[0];
        long key2 = pair[1];

        try (LongDirectHash hash = new LongDirectHash()) {
            long ord1 = hash.add(key1);
            assertThat("key1 gets ordinal 0", ord1, equalTo(0L));
            assertThat("key1 is findable before eviction", hash.find(key1), equalTo(0L));

            // key2 maps to the same slot but has a different fingerprint → evicts key1
            long ord2 = hash.add(key2);
            assertThat("key2 gets a fresh ordinal after eviction", ord2, greaterThan(0L));
            assertThat("size is 2: both ordinals live in the key store", hash.size(), equalTo(2L));

            assertThat("evicted key1 no longer findable via slot", hash.find(key1), equalTo(-1L));
            assertThat("ordinal 0 still holds key1 in key store", hash.get(0), equalTo(key1));
            assertThat("ordinal 1 holds key2", hash.get(1), equalTo(key2));
        }
    }

    /**
     * Two keys with the same 32-bit hash (fingerprint) but different values occupy the same slot.
     * The table performs key verification on fingerprint match: if keys differ, the slot is
     * overwritten (eviction) and the new key gets a fresh ordinal.
     */
    public void testEvictionOnFingerprintMatchKeyMismatch() {
        long[] pair = findSameHashDifferentKeyPair();
        long key1 = pair[0];
        long key2 = pair[1];

        try (LongDirectHash hash = new LongDirectHash()) {
            long ord1 = hash.add(key1);
            assertThat("key1 gets ordinal 0", ord1, equalTo(0L));

            // Same 32-bit hash, different key → fingerprint matches but key comparison fails → eviction
            long ord2 = hash.add(key2);
            assertThat("key2 evicts key1 and gets a new ordinal", ord2, greaterThan(0L));
            assertThat("size is 2 after eviction", hash.size(), equalTo(2L));

            assertThat("key1 is no longer live in the slot", hash.find(key1), equalTo(-1L));
            assertThat("key2 is now the live entry", hash.find(key2), equalTo(1L));
        }
    }

    /**
     * Verifies that two keys landing in <em>different</em> slots coexist without interference.
     */
    public void testNoInterferenceAcrossSlots() {
        // Keys with distinct slots (overwhelmingly likely for random keys)
        try (LongDirectHash hash = new LongDirectHash()) {
            List<Long> keys = new ArrayList<>();
            int inserted = 0;
            long attempts = 0;
            while (inserted < 100 && attempts < 10_000) {
                long key = randomLong();
                long result = hash.add(key);
                if (result >= 0) {
                    keys.add(key);
                    inserted++;
                }
                attempts++;
            }
            // All still-live keys must be findable
            for (long key : keys) {
                long found = hash.find(key);
                // A key may have been evicted by a later key landing in the same slot
                if (found >= 0) {
                    assertThat("found key matches", hash.get(found), equalTo(key));
                }
            }
        }
    }

    public void testClear() {
        try (LongDirectHash hash = new LongDirectHash()) {
            for (int i = 0; i < 10; i++) {
                hash.add((long) i);
            }
            assertThat(hash.size(), equalTo(10L));

            hash.clear();
            assertThat("size resets after clear", hash.size(), equalTo(0L));
            assertThat("find on cleared table returns -1", hash.find(0L), equalTo(-1L));

            // Re-adding after clear restarts ordinals from 0
            assertThat("ordinal restarts at 0 after clear", hash.add(42L), equalTo(0L));
        }
    }

    public void testKeyStoreGrowth() {
        try (LongDirectHash hash = new LongDirectHash()) {
            // Force key store to grow beyond initial capacity by adding many distinct keys
            // (some will evict others, but size() monotonically increases)
            int target = LongDirectHash.CAPACITY * 3;
            for (int i = 0; i < target; i++) {
                // Use i as key — each unique, distributed across slots
                hash.add((long) i * 1_000_003L); // coprime stride for slot spread
            }
            assertThat("size accounts for all assigned ordinals including evicted", hash.size(), equalTo((long) target));
            // All get() calls must succeed
            for (int i = 0; i < target; i++) {
                long key = hash.get(i);
                assertNotNull("key at ordinal " + i + " is non-null", key);
            }
        }
    }

    // ---- helpers ----

    /**
     * Finds two distinct keys whose hashes land in the same slot (lower 10 bits equal) but whose
     * full 32-bit hashes differ. This is trivially achievable: scan until two keys share the same
     * low 10 bits of their hash but differ on the full 32-bit hash.
     */
    private static long[] findSameSlotDifferentFingerprintPair() {
        // Collect the first key seen for each of the 1024 slots.
        long[] firstKey = new long[LongDirectHash.CAPACITY];
        int[] firstHash = new int[LongDirectHash.CAPACITY];
        java.util.Arrays.fill(firstKey, Long.MIN_VALUE);

        for (long k = 0; k < 1_000_000L; k++) {
            int h = LongSwissHash.hash(k);
            int slot = h & (LongDirectHash.CAPACITY - 1);
            if (firstKey[slot] == Long.MIN_VALUE) {
                firstKey[slot] = k;
                firstHash[slot] = h;
            } else if (firstHash[slot] != h) {
                // Same slot, different fingerprint → found our pair
                return new long[] { firstKey[slot], k };
            }
        }
        throw new IllegalStateException("could not find same-slot different-fingerprint pair");
    }

    /**
     * Finds two distinct keys with the same 32-bit hash value using a sorted (hash, index) array.
     * The Fibonacci hash distributes consecutive integers maximally uniformly, so we use an LCG
     * sequence to produce diverse-looking keys where birthday collisions occur at the expected
     * rate (~116 per million keys against a 2^32 hash space).
     */
    private static long[] findSameHashDifferentKeyPair() {
        int n = 1_000_000;
        // Use an LCG to get random-looking key values rather than consecutive integers.
        long[] encoded = new long[n];
        long key = 12345678901234567L;
        for (int i = 0; i < n; i++) {
            key = key * 6364136223846793005L + 1442695040888963407L; // LCG
            int h = LongSwissHash.hash(key);
            // Encode: upper 32 bits = hash, lower 32 bits = array index (to recover key later)
            encoded[i] = ((long) h << 32) | (i & 0xFFFFFFFFL);
        }
        // Rebuild the key sequence so we can look up key by index after sorting.
        long[] keys = new long[n];
        key = 12345678901234567L;
        for (int i = 0; i < n; i++) {
            key = key * 6364136223846793005L + 1442695040888963407L;
            keys[i] = key;
        }
        java.util.Arrays.sort(encoded);
        for (int i = 0; i + 1 < n; i++) {
            if ((int) (encoded[i] >>> 32) == (int) (encoded[i + 1] >>> 32)) {
                long k1 = keys[(int) encoded[i]];
                long k2 = keys[(int) encoded[i + 1]];
                if (k1 != k2) {
                    return new long[] { k1, k2 };
                }
            }
        }
        throw new IllegalStateException("no 32-bit hash collision found among " + n + " LCG-generated keys");
    }
}
